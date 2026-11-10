package com.adtek.sn.service.impl;

import com.adtek.common.core.BusinessException;
import com.adtek.common.core.ErrorCode;
import com.adtek.framework.security.AuthErrorCode;
import com.adtek.framework.security.AuthPrincipal;
import com.adtek.framework.security.SessionService;
import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.domain.UserEntity;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.dto.UserCreateDTO;
import com.adtek.sn.dto.UserUpdateDTO;
import com.adtek.sn.mapper.UserMapper;
import com.adtek.sn.query.UserQuery;
import com.adtek.sn.service.AuthService;
import com.adtek.sn.service.UserService;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.Passwords;
import com.adtek.sn.support.SecretBox;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Trace;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.LoginVO;
import com.adtek.sn.vo.PageVO;
import com.adtek.sn.vo.SecretVO;
import com.adtek.sn.vo.UserFactoryVO;
import com.adtek.sn.vo.UserVO;

import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 登录、改密与账户管理（原系统 app/services/users.py）。
 *
 * <p>页面账户用工号和密码登录，不强制改密；系统管理员只有一个（工号 SN_INIT_ADMIN_EMP_NO）；普通操作员、审计员必须至少授权一个生产组织；
 * 改密、重置后该账户所有会话失效。MES 接口账户不能登录页面：系统生成密钥（加密保存，可查看、可重置），每次请求带工号 + 密钥；连续失败锁定， 可配置来源地址白名单。
 */
@Service
public class UserServiceImpl implements UserService, AuthService {
    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";
    private static final Pattern EMP_NO = Pattern.compile("^[A-Za-z0-9-]{3,32}$");
    private static final Set<String> LANGS = Set.of("zh-CN", "en", "vi");
    private static final Pattern IP = Pattern.compile("^[0-9A-Fa-f:.]+(/\\d{1,3})?$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMapper mapper;
    private final FactoryServiceImpl factories;
    private final AuditServiceImpl audit;
    private final SessionService sessions;
    private final SecretBox secretBox;
    private final SnSettings settings;
    private final Tx tx;
    private volatile String dummyHash;

    /** 已验过的 MES 密钥：账户 id → (口令版本, 密钥摘要)；重置密钥会改口令版本，缓存随之失效。 */
    private final Map<Long, Verified> verified = new ConcurrentHashMap<>();

    private record Verified(long pv, byte[] digest) {}

    public UserServiceImpl(
            UserMapper mapper,
            FactoryServiceImpl factories,
            AuditServiceImpl audit,
            SessionService sessions,
            SecretBox secretBox,
            SnSettings settings,
            Tx tx) {
        this.mapper = mapper;
        this.factories = factories;
        this.audit = audit;
        this.sessions = sessions;
        this.secretBox = secretBox;
        this.settings = settings;
        this.tx = tx;
    }

    private CurrentUser cu() {
        return CurrentUser.current(settings.initAdminEmpNo());
    }

    // ------------------------------------------------------------------ 视图

    private UserVO view(UserEntity u, Map<String, String> names, List<String> bound) {
        List<String> codes = u.factoryCode != null ? List.of(u.factoryCode) : bound;
        boolean mes = Util.MES.equals(u.role);
        UserVO v = new UserVO();
        v.id = u.id;
        v.empNo = u.empNo;
        v.name = u.name;
        v.role = u.role;
        v.factoryCode = u.factoryCode;
        v.factoryName = u.factoryCode != null ? names.get(u.factoryCode) : null;
        v.factoryCodes = codes;
        v.factoryNames = codes.stream().map(c -> names.getOrDefault(c, c)).toList();
        v.status = u.status;
        v.superAdmin = Util.isSuperAdmin(u.empNo, u.role, settings.initAdminEmpNo());
        v.ipWhitelist = mes ? u.ipWhitelist : null;
        v.lockedUntil = mes ? u.lockedUntil : null;
        v.tzOffsetMin = factories.tzOffset(codes);
        v.lang = u.lang;
        v.lastLoginAt = u.lastLoginAt;
        v.createdAt = u.createdAt;
        v.createdBy = u.createdBy;
        v.disabledAt = u.disabledAt;
        v.disabledBy = u.disabledBy;
        return v;
    }

    public List<String> boundFactories(long uid) {
        return mapper.boundFactories(uid);
    }

    /** 账户授权的生产组织；系统管理员为空 = 全部（其他角色为空 = 一个也没有）。 */
    public List<String> readScope(long uid, String role, String factoryCode) {
        if (Util.MES.equals(role)) {
            return factoryCode != null ? List.of(factoryCode) : List.of();
        }
        if (Util.SCOPED_ROLES.contains(role)) {
            return boundFactories(uid);
        }
        return List.of();
    }

    public UserVO view(UserEntity u) {
        return view(
                u,
                factories.names(),
                Util.SCOPED_ROLES.contains(u.role) ? boundFactories(u.id) : List.of());
    }

    private UserEntity mustGet(long uid) {
        UserEntity u = mapper.byId(uid);
        if (u == null) {
            throw Biz.err(SnErrorCode.USER_NOT_FOUND);
        }
        return u;
    }

    /** 版本时间（秒）必须严格递增：不晚于上一次时顺延一秒。 */
    static LocalDateTime nextVersion(LocalDateTime prev, LocalDateTime now) {
        return prev != null && !now.isAfter(prev) ? prev.plusSeconds(1) : now;
    }

    private String dummy() {
        if (dummyHash == null) {
            dummyHash = Passwords.hash(Util.tokenHex());
        }
        return dummyHash;
    }

    // ------------------------------------------------------------------ 登录

    private CurrentUser loginActor(String emp, UserEntity u) {
        if (u == null) {
            String e = Util.cut(emp, 32);
            return new CurrentUser(
                    null,
                    e == null || e.isEmpty() ? "-" : e,
                    "",
                    "",
                    null,
                    CurrentUser.PAGE,
                    Trace.id(),
                    List.of(),
                    false);
        }
        return new CurrentUser(
                u.id,
                u.empNo,
                u.name,
                u.role,
                u.factoryCode,
                CurrentUser.PAGE,
                Trace.id(),
                List.of(),
                false);
    }

    private BusinessException loginFailed(CurrentUser actor, ErrorCode code, String detail) {
        BusinessException e = new BusinessException(code);
        String message =
                code instanceof SnErrorCode sn
                        ? sn.zh()
                        : code instanceof AuthErrorCode a ? a.message() : code.key();
        audit.recordIsolated(
                actor,
                AuditEntryDTO.of(AuditAction.LOGIN)
                        .factory(actor.factoryCode())
                        .fail(message)
                        .info(detail));
        return e;
    }

    @Override
    public LoginResult login(String empNo, String password, String client, boolean page) {
        String emp = Util.trim(empNo);
        String where = "client=" + (Util.blank(client) ? "-" : client);
        UserEntity u = emp.isEmpty() ? null : mapper.byEmpNo(emp);
        boolean matched = Passwords.verify(password, u == null ? dummy() : u.passwordHash);
        if (u == null || !matched) {
            throw loginFailed(loginActor(emp, u), SnErrorCode.LOGIN_FAILED, where);
        }
        if (!ACTIVE.equals(u.status)) {
            throw loginFailed(loginActor(emp, u), AuthErrorCode.ACCOUNT_DISABLED, where);
        }
        if (Util.MES.equals(u.role)) {
            throw loginFailed(loginActor(emp, u), SnErrorCode.MES_NO_PAGE_LOGIN, where);
        }
        if (Util.SCOPED_ROLES.contains(u.role) && boundFactories(u.id).isEmpty()) {
            throw loginFailed(loginActor(emp, u), AuthErrorCode.USER_NO_FACTORY, where);
        }
        SessionService.Issued issued =
                tx.run(
                        () -> {
                            if (page) {
                                LocalDateTime prev = mapper.lockLastLogin(u.id);
                                u.lastLoginAt = nextVersion(prev, Util.now());
                                mapper.setLastLogin(u.id, u.lastLoginAt);
                            }
                            audit.record(
                                    loginActor(emp, u),
                                    AuditEntryDTO.of(AuditAction.LOGIN)
                                            .factory(u.factoryCode)
                                            .info(where));
                            return sessions.issue(
                                    u.id,
                                    Passwords.version(u.pwdChangedAt),
                                    page ? SessionService.Kind.PAGE : SessionService.Kind.API);
                        });
        LoginVO body = new LoginVO();
        body.user = view(u);
        body.token = page ? null : issued.token();
        return new LoginResult(body, issued.token(), sessions.ttlSeconds(), page);
    }

    @Override
    public UserVO me() {
        return view(mustGet(cu().id()));
    }

    @Override
    public void changePassword(String oldPassword, String newPassword) {
        CurrentUser cu = cu();
        tx.exec(
                () -> {
                    UserEntity u = mustGet(cu.id());
                    if (!Passwords.verify(oldPassword, u.passwordHash)) {
                        throw Biz.err(SnErrorCode.USER_PASSWORD_WRONG);
                    }
                    Passwords.checkPolicy(newPassword);
                    if (Passwords.verify(newPassword, u.passwordHash)) {
                        throw Biz.err(SnErrorCode.USER_PASSWORD_SAME);
                    }
                    LocalDateTime now = Util.now();
                    mapper.setPassword(
                            u.id,
                            Passwords.hash(newPassword),
                            nextVersion(u.pwdChangedAt, now),
                            now);
                    sessions.revokeAll(u.id, SessionService.REASON_PASSWORD);
                    audit.record(cu, AuditEntryDTO.of(AuditAction.PASSWORD_CHANGE).info(u.empNo));
                });
    }

    @Override
    public UserVO setLang(String lang) {
        CurrentUser cu = cu();
        String lng = Util.trimOrNull(lang);
        if (lng != null && !LANGS.contains(lng)) {
            lng = null;
        }
        mapper.setLang(cu.id(), lng);
        return view(mustGet(cu.id()));
    }

    @Override
    public void logout() {
        AuthPrincipal.current()
                .map(AuthPrincipal::sessionId)
                .filter(Objects::nonNull)
                .ifPresent(id -> sessions.revoke(id, SessionService.REASON_LOGOUT));
    }

    // ------------------------------------------------------------------ 账户管理

    @Override
    public PageVO<UserVO> list(UserQuery q) {
        cu().requireSuperAdmin();
        PageReq page = PageReq.of(q.page, q.pageSize);
        String kind = Util.trim(q.kind);
        String text = Util.trimOrNull(q.q);
        String role = Util.trimOrNull(q.role);
        String status = Util.trimOrNull(q.status);
        String factory = Util.trimOrNull(q.factoryCode);
        String k = "page".equals(kind) || "mes".equals(kind) ? kind : null;
        String prefix = text == null ? null : text + "%";
        String like = text == null ? null : "%" + text + "%";
        long total = mapper.count(k, prefix, like, role, status, factory);
        List<UserEntity> rows =
                mapper.page(k, prefix, like, role, status, factory, page.offset(), page.size());
        Map<String, String> names = factories.names();
        Map<Long, List<String>> bound = new HashMap<>();
        if (!rows.isEmpty()) {
            for (UserFactoryVO r : mapper.boundOf(rows.stream().map(u -> u.id).toList())) {
                bound.computeIfAbsent(r.userId, x -> new ArrayList<>()).add(r.factoryCode);
            }
        }
        List<UserVO> items =
                rows.stream()
                        .map(u -> view(u, names, bound.getOrDefault(u.id, List.of())))
                        .toList();
        return PageVO.of(items, total, page.page(), page.size());
    }

    /** factory = MES 接口账户的生产组织；scope = 普通操作员、审计员授权的生产组织（至少一个）。 */
    private record Binding(String factory, List<String> scope) {
        List<String> codes() {
            return factory != null ? List.of(factory) : scope;
        }

        String text() {
            if (factory != null) {
                return factory;
            }
            return scope.isEmpty() ? "*" : String.join(",", scope);
        }
    }

    private Binding checkRoleFactory(
            String role,
            String factoryCode,
            List<String> factoryCodes,
            List<String> current,
            boolean adminOk) {
        if (!Util.ROLES.contains(role)) {
            throw Biz.err(SnErrorCode.USER_ROLE_INVALID);
        }
        String f = Util.trimOrNull(factoryCode);
        Set<String> manySet = new LinkedHashSet<>();
        if (factoryCodes != null) {
            for (String x : factoryCodes) {
                String c = Util.trim(x);
                if (!c.isEmpty()) {
                    manySet.add(c);
                }
            }
        }
        List<String> many = new ArrayList<>(manySet);
        if (Util.ADMIN.equals(role)) {
            if (!adminOk) {
                throw Biz.err(SnErrorCode.USER_ADMIN_UNIQUE);
            }
            if (f != null || !many.isEmpty()) {
                throw Biz.err(SnErrorCode.USER_ADMIN_NO_FACTORY);
            }
            return new Binding(null, List.of());
        }
        if (Util.MES.equals(role)) {
            if (f == null && many.size() == 1) {
                f = many.getFirst();
            }
            if (f == null || many.size() > 1) {
                throw Biz.err(SnErrorCode.USER_MES_FACTORY_REQUIRED);
            }
            if (!current.contains(f)) {
                factories.mustBeEnabled(f);
            }
            return new Binding(f, List.of());
        }
        List<String> codes =
                factoryCodes != null ? many : (f != null ? List.of(f) : List.<String>of());
        if (codes.isEmpty()) {
            throw Biz.err(SnErrorCode.USER_FACTORY_REQUIRED);
        }
        for (String c : codes) {
            if (!current.contains(c)) {
                factories.mustBeEnabled(c);
            }
        }
        return new Binding(null, List.copyOf(new TreeSet<>(codes)));
    }

    /** 来源地址白名单：逗号或空白分隔的 IP / CIDR；空 = 不限。 */
    static String ipList(String raw) {
        Set<String> items = new LinkedHashSet<>();
        for (String x : Util.trim(raw).split("[\\s,;]+")) {
            if (!x.isEmpty()) {
                items.add(x);
            }
        }
        for (String x : items) {
            if (!IP.matcher(x).matches() || Cidr.parse(x) == null) {
                throw Biz.err(SnErrorCode.USER_IP_LIST_INVALID, "value", x);
            }
        }
        String out = String.join(",", items);
        if (out.length() > 1000) {
            throw Biz.err(SnErrorCode.USER_IP_LIST_INVALID, "value", out.substring(0, 40) + "…");
        }
        return out;
    }

    static boolean ipAllowed(String whitelist, String client) {
        if (whitelist == null || whitelist.isEmpty()) {
            return true;
        }
        if (client == null || client.isEmpty()) {
            return false;
        }
        byte[] addr = Cidr.address(client);
        if (addr == null) {
            return false;
        }
        for (String x : whitelist.split(",")) {
            Cidr c = Cidr.parse(x);
            if (c != null && c.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    /** IP 网段（与 Python ipaddress.ip_network(strict=False) 一致）。 */
    record Cidr(byte[] network, int prefix) {
        static byte[] address(String text) {
            if (text == null || text.isEmpty() || !text.matches("[0-9A-Fa-f:.]+")) {
                return null;
            }
            if (text.contains(".") && !text.contains(":")) {
                String[] p = text.split("\\.", -1);
                if (p.length != 4) {
                    return null;
                }
                byte[] b = new byte[4];
                for (int i = 0; i < 4; i++) {
                    if (p[i].isEmpty() || p[i].length() > 3 || !p[i].matches("\\d+")) {
                        return null;
                    }
                    int v = Integer.parseInt(p[i]);
                    if (v > 255 || (p[i].length() > 1 && p[i].startsWith("0"))) {
                        return null;
                    }
                    b[i] = (byte) v;
                }
                return b;
            }
            try {
                byte[] b = InetAddress.getByName(text).getAddress();
                return b.length == 16 ? b : null;
            } catch (UnknownHostException e) {
                return null;
            }
        }

        static Cidr parse(String text) {
            String t = text.strip();
            int slash = t.indexOf('/');
            byte[] addr = address(slash < 0 ? t : t.substring(0, slash));
            if (addr == null) {
                return null;
            }
            int max = addr.length * 8;
            int prefix = max;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(t.substring(slash + 1));
                } catch (NumberFormatException e) {
                    return null;
                }
                if (prefix < 0 || prefix > max) {
                    return null;
                }
            }
            return new Cidr(addr, prefix);
        }

        boolean contains(byte[] addr) {
            if (addr.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xff << (8 - rest)) & 0xff;
            return (addr[full] & mask) == (network[full] & mask);
        }
    }

    static String newSecret() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void saveBinding(long uid, Binding b) {
        mapper.deleteBindings(uid);
        for (String c : b.scope()) {
            mapper.insertBinding(uid, c);
        }
    }

    private void checkNotLastAdmin(UserEntity target) {
        if (!Util.ADMIN.equals(target.role) || !ACTIVE.equals(target.status)) {
            return;
        }
        if (mapper.activeAdmins() <= 1) {
            throw Biz.err(SnErrorCode.USER_LAST_ADMIN);
        }
    }

    private static String nameOf(String name) {
        String n = Util.trim(name);
        if (n.isEmpty() || n.length() > 64) {
            throw Biz.err(SnErrorCode.USER_NAME_REQUIRED);
        }
        return n;
    }

    @Override
    public UserVO create(UserCreateDTO body) {
        CurrentUser cu = cu().requireSuperAdmin();
        return tx.run(
                () -> {
                    String emp = Util.trim(body.empNo);
                    if (!EMP_NO.matcher(emp).matches()) {
                        throw Biz.err(SnErrorCode.USER_EMP_NO_INVALID);
                    }
                    String n = nameOf(body.name);
                    String r = Util.trim(body.role);
                    Binding b =
                            checkRoleFactory(
                                    r, body.factoryCode, body.factoryCodes, List.of(), false);
                    String secret = null;
                    String password = body.password;
                    String ips;
                    if (Util.MES.equals(r)) {
                        secret = newSecret();
                        password = secret;
                        ips = ipList(body.ipWhitelist);
                    } else {
                        Passwords.checkPolicy(password);
                        ips = "";
                    }
                    if (mapper.byEmpNo(emp) != null) {
                        throw Biz.err(SnErrorCode.USER_EMP_NO_EXISTS, "emp_no", emp);
                    }
                    LocalDateTime now = Util.now();
                    UserEntity e = new UserEntity();
                    e.empNo = emp;
                    e.name = n;
                    e.role = r;
                    e.factoryCode = b.factory();
                    e.status = ACTIVE;
                    e.passwordHash = Passwords.hash(password);
                    e.secretSealed = secret != null ? secretBox.seal(secret) : "";
                    e.pwdChangedAt = now;
                    e.ipWhitelist = ips;
                    e.createdBy = cu.empNo();
                    e.createdAt = now;
                    e.updatedAt = now;
                    mapper.insert(e);
                    saveBinding(e.id, b);
                    audit.record(
                            cu,
                            AuditEntryDTO.of(AuditAction.USER_CREATE)
                                    .factory(b.factory())
                                    .info(
                                            "emp_no="
                                                    + emp
                                                    + " role="
                                                    + r
                                                    + " factory="
                                                    + b.text()
                                                    + (secret != null ? " secret=generated" : "")));
                    UserVO out = view(mustGet(e.id));
                    out.secret = secret;
                    return out;
                });
    }

    @Override
    public UserVO update(long uid, UserUpdateDTO body) {
        CurrentUser cu = cu().requireSuperAdmin();
        return tx.run(
                () -> {
                    UserEntity u = mustGet(uid);
                    Binding old = new Binding(u.factoryCode, boundFactories(uid));
                    String n = nameOf(body.name);
                    String r = Util.trim(body.role);
                    if (r.isEmpty()) {
                        r = u.role;
                    }
                    if (Util.MES.equals(r) != Util.MES.equals(u.role)) {
                        throw Biz.err(SnErrorCode.USER_ROLE_FIXED);
                    }
                    boolean superAdmin = u.empNo.equals(settings.initAdminEmpNo());
                    if (superAdmin && !Util.ADMIN.equals(r)) {
                        throw Biz.err(SnErrorCode.USER_SUPER_ADMIN_FIXED);
                    }
                    Binding b =
                            checkRoleFactory(
                                    r,
                                    body.factoryCode,
                                    body.factoryCodes,
                                    old.codes(),
                                    superAdmin);
                    if (Util.ADMIN.equals(u.role) && !Util.ADMIN.equals(r)) {
                        checkNotLastAdmin(u);
                    }
                    String ips =
                            Util.MES.equals(r) && body.ipWhitelist != null
                                    ? ipList(body.ipWhitelist)
                                    : u.ipWhitelist;
                    mapper.update(uid, n, r, b.factory(), ips, Util.now());
                    saveBinding(uid, b);
                    audit.record(
                            cu,
                            AuditEntryDTO.of(AuditAction.USER_UPDATE)
                                    .factory(b.factory())
                                    .info(
                                            "emp_no="
                                                    + u.empNo
                                                    + " role="
                                                    + u.role
                                                    + "->"
                                                    + r
                                                    + " factory="
                                                    + old.text()
                                                    + "->"
                                                    + b.text()
                                                    + (Util.MES.equals(r)
                                                            ? " ip="
                                                                    + (Util.blank(u.ipWhitelist)
                                                                            ? "*"
                                                                            : u.ipWhitelist)
                                                                    + "->"
                                                                    + (Util.blank(ips) ? "*" : ips)
                                                            : "")));
                    return view(mustGet(uid));
                });
    }

    @Override
    public UserVO resetSecret(long uid) {
        CurrentUser cu = cu().requireSuperAdmin();
        return tx.run(
                () -> {
                    UserEntity u = mustGet(uid);
                    if (!Util.MES.equals(u.role)) {
                        throw Biz.err(SnErrorCode.USER_NOT_MES);
                    }
                    String secret = newSecret();
                    LocalDateTime now = Util.now();
                    mapper.resetSecret(
                            uid,
                            Passwords.hash(secret),
                            secretBox.seal(secret),
                            nextVersion(u.pwdChangedAt, now),
                            now);
                    sessions.revokeAll(uid, SessionService.REASON_PASSWORD);
                    audit.record(
                            cu,
                            AuditEntryDTO.of(AuditAction.USER_RESET_SECRET)
                                    .factory(u.factoryCode)
                                    .info("emp_no=" + u.empNo));
                    UserVO out = view(mustGet(uid));
                    out.secret = secret;
                    return out;
                });
    }

    @Override
    public SecretVO viewSecret(long uid) {
        CurrentUser cu = cu().requireSuperAdmin();
        UserEntity u = mustGet(uid);
        if (!Util.MES.equals(u.role)) {
            throw Biz.err(SnErrorCode.USER_NOT_MES);
        }
        String secret = secretBox.open(u.secretSealed);
        if (secret == null || secret.isEmpty()) {
            throw Biz.err(SnErrorCode.USER_SECRET_UNAVAILABLE);
        }
        audit.recordIsolated(
                cu,
                AuditEntryDTO.of(AuditAction.USER_VIEW_SECRET)
                        .factory(u.factoryCode)
                        .info("emp_no=" + u.empNo));
        SecretVO v = new SecretVO();
        v.id = u.id;
        v.empNo = u.empNo;
        v.secret = secret;
        return v;
    }

    @Override
    public UserVO disable(long uid) {
        CurrentUser cu = cu().requireSuperAdmin();
        return tx.run(
                () -> {
                    UserEntity u = mustGet(uid);
                    if (Objects.equals(u.id, cu.id())) {
                        throw Biz.err(SnErrorCode.USER_SELF_DISABLE);
                    }
                    if (u.empNo.equals(settings.initAdminEmpNo())) {
                        throw Biz.err(SnErrorCode.USER_SUPER_ADMIN_FIXED);
                    }
                    if (DISABLED.equals(u.status)) {
                        return view(u);
                    }
                    checkNotLastAdmin(u);
                    mapper.disable(uid, cu.empNo(), Util.now());
                    audit.record(
                            cu,
                            AuditEntryDTO.of(AuditAction.USER_DISABLE)
                                    .factory(u.factoryCode)
                                    .info("emp_no=" + u.empNo));
                    return view(mustGet(uid));
                });
    }

    @Override
    public UserVO enable(long uid) {
        CurrentUser cu = cu().requireSuperAdmin();
        return tx.run(
                () -> {
                    UserEntity u = mustGet(uid);
                    if (ACTIVE.equals(u.status)) {
                        return view(u);
                    }
                    mapper.enable(uid, Util.now());
                    audit.record(
                            cu,
                            AuditEntryDTO.of(AuditAction.USER_ENABLE)
                                    .factory(u.factoryCode)
                                    .info("emp_no=" + u.empNo));
                    return view(mustGet(uid));
                });
    }

    @Override
    public UserVO resetPassword(long uid, String password) {
        CurrentUser cu = cu().requireSuperAdmin();
        return tx.run(
                () -> {
                    UserEntity u = mustGet(uid);
                    if (Util.MES.equals(u.role)) {
                        throw Biz.err(SnErrorCode.USER_ROLE_FIXED);
                    }
                    Passwords.checkPolicy(password);
                    LocalDateTime now = Util.now();
                    mapper.setPassword(
                            uid, Passwords.hash(password), nextVersion(u.pwdChangedAt, now), now);
                    sessions.revokeAll(uid, SessionService.REASON_PASSWORD);
                    audit.record(
                            cu,
                            AuditEntryDTO.of(AuditAction.USER_RESET_PWD)
                                    .factory(u.factoryCode)
                                    .info("emp_no=" + u.empNo));
                    return view(mustGet(uid));
                });
    }

    // ------------------------------------------------------------------ 启动

    private String adminEmpNo() {
        String emp = settings.initAdminEmpNo();
        if (!EMP_NO.matcher(emp).matches()) {
            throw new IllegalStateException(
                    "SN_INIT_ADMIN_EMP_NO=" + emp + " 不是合法工号（3–32 位字母、数字和 -）");
        }
        return emp;
    }

    private void insertAdmin(String emp, String password, LocalDateTime now) {
        UserEntity e = new UserEntity();
        e.empNo = emp;
        e.name = "系统管理员";
        e.role = Util.ADMIN;
        e.status = ACTIVE;
        e.passwordHash = Passwords.hash(password);
        e.secretSealed = "";
        e.ipWhitelist = "";
        e.createdBy = "system";
        e.createdAt = now;
        e.updatedAt = now;
        mapper.insert(e);
    }

    /** 运维恢复：把系统管理员重置为配置的密码并启用、角色改回管理员；不存在就新建。 */
    public void resetAdmin(String password) {
        String emp = adminEmpNo();
        tx.exec(
                () -> {
                    UserEntity u = mapper.byEmpNo(emp);
                    LocalDateTime now = Util.now();
                    if (u == null) {
                        insertAdmin(emp, password, now);
                    } else {
                        mapper.restoreAdmin(
                                u.id,
                                Passwords.hash(password),
                                nextVersion(u.pwdChangedAt, now),
                                now);
                        mapper.deleteBindings(u.id);
                        sessions.revokeAll(u.id, SessionService.REASON_PASSWORD);
                    }
                    audit.record(
                            CurrentUser.system(),
                            AuditEntryDTO.of(AuditAction.USER_RESET_PWD)
                                    .info("emp_no=" + emp + " SN_RESET_ADMIN"));
                });
    }

    public boolean needsBootstrap() {
        return mapper.countAll() == 0;
    }

    /** 空库启动时按配置建首个管理员。 */
    public boolean bootstrapAdmin(String password) {
        String emp = adminEmpNo();
        return tx.run(
                () -> {
                    if (!needsBootstrap()) {
                        return false;
                    }
                    insertAdmin(emp, password, Util.now());
                    audit.record(
                            CurrentUser.system(),
                            AuditEntryDTO.of(AuditAction.USER_CREATE)
                                    .info("emp_no=" + emp + " bootstrap"));
                    return true;
                });
    }

    // ================================================================== MES 接口账户认证

    /** MES 认证失败（按 MES 错误结构返回）。 */
    public static final class MesAuthException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int status;
        public final String code;

        MesAuthException(int status, String code, String message) {
            super(message, null, false, false);
            this.status = status;
            this.code = code;
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * MES 接口的认证：工号 + 密钥（每次请求都验），返回账户。401 INVALID_CREDENTIALS；连续错 N 次锁定 M 分钟，锁定期间 423
     * ACCOUNT_LOCKED；403 IP_NOT_ALLOWED / ACCOUNT_DISABLED。失败、锁定、解锁都留痕。
     */
    public UserEntity mesAuthenticate(String empNo, String secret, String client) {
        String emp = Util.trim(empNo);
        UserEntity found = emp.isEmpty() ? null : mapper.byEmpNo(emp);
        UserEntity u = found != null && Util.MES.equals(found.role) ? found : null;
        String cutEmp = Util.cut(emp, 32);
        CurrentUser actor =
                new CurrentUser(
                        u == null ? null : u.id,
                        cutEmp == null || cutEmp.isEmpty() ? "-" : cutEmp,
                        u == null ? "" : u.name,
                        Util.MES,
                        u == null ? null : u.factoryCode,
                        CurrentUser.API,
                        Trace.id(),
                        List.of(),
                        false);
        String where = "client=" + (Util.blank(client) ? "-" : client);
        long pv = u == null ? 0 : Passwords.version(u.pwdChangedAt);
        byte[] digest = sha256(secret == null ? "" : secret);
        Verified cached = u == null ? null : verified.get(u.id);
        boolean matched =
                cached != null
                                && cached.pv() == pv
                                && MessageDigest.isEqual(cached.digest(), digest)
                        || Passwords.verify(secret, u == null ? dummy() : u.passwordHash);
        if (u == null) {
            throw mesFail(actor, where, 401, "INVALID_CREDENTIALS", "工号或密钥错误");
        }
        if (!ACTIVE.equals(u.status)) {
            throw mesFail(actor, where, 403, "ACCOUNT_DISABLED", "账户已停用");
        }
        if (!ipAllowed(u.ipWhitelist, client)) {
            throw mesFail(
                    actor,
                    where,
                    403,
                    "IP_NOT_ALLOWED",
                    "来源地址 " + (Util.blank(client) ? "-" : client) + " 不在白名单内");
        }
        LocalDateTime now = Util.now();
        if (matched && u.authFailures == 0 && u.lockedUntil == null) {
            verified.put(u.id, new Verified(pv, digest));
            if (u.lastLoginAt == null || !now.isBefore(u.lastLoginAt.plusMinutes(1))) {
                mapper.setLastLogin(u.id, now);
            }
            return u;
        }
        MesAuthException failure =
                tx.run(
                        () -> {
                            UserEntity row = mapper.lockAuth(u.id);
                            LocalDateTime locked = row.lockedUntil;
                            if (locked != null && locked.isAfter(now)) {
                                return new MesAuthException(
                                        423,
                                        "ACCOUNT_LOCKED",
                                        "连续认证失败已锁定，" + Util.fmt(locked) + " 后解锁");
                            }
                            int failures = row.authFailures;
                            if (locked != null) {
                                mapper.unlockAuth(u.id);
                                audit.record(
                                        actor,
                                        AuditEntryDTO.of(AuditAction.MES_AUTH_UNLOCK)
                                                .factory(u.factoryCode)
                                                .info(where));
                                failures = 0;
                            }
                            if (!matched) {
                                failures++;
                                boolean lock = failures >= settings.mesAuthMaxFailures();
                                LocalDateTime until =
                                        lock
                                                ? now.plusMinutes(settings.mesAuthLockMinutes())
                                                : null;
                                mapper.setAuthFailures(u.id, lock ? 0 : failures, until);
                                if (lock) {
                                    audit.record(
                                            actor,
                                            AuditEntryDTO.of(AuditAction.MES_AUTH_LOCK)
                                                    .factory(u.factoryCode)
                                                    .info(where + " until=" + Util.fmt(until)));
                                }
                            } else {
                                mapper.authOk(u.id, now);
                            }
                            return null;
                        });
        if (failure != null) {
            throw mesFail(actor, where, failure.status, failure.code, failure.getMessage());
        }
        if (!matched) {
            throw mesFail(actor, where, 401, "INVALID_CREDENTIALS", "工号或密钥错误");
        }
        verified.put(u.id, new Verified(pv, digest));
        return u;
    }

    private MesAuthException mesFail(
            CurrentUser actor, String where, int status, String code, String message) {
        audit.recordIsolated(
                actor,
                AuditEntryDTO.of(AuditAction.MES_AUTH)
                        .factory(actor.factoryCode())
                        .fail(message)
                        .info(where));
        return new MesAuthException(status, code, message);
    }

    /** 当前授权（供 PrincipalPort）。 */
    UserEntity byId(long id) {
        return mapper.byId(id);
    }
}
