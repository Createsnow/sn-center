package com.adtek.sn.service.impl;

import com.adtek.sn.constant.AuditAction;
import com.adtek.sn.constant.SnErrorCode;
import com.adtek.sn.dto.AuditEntryDTO;
import com.adtek.sn.mapper.SnImportMapper;
import com.adtek.sn.service.SnImportService;
import com.adtek.sn.support.Biz;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.DbErrors;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.R;
import com.adtek.sn.support.SnCodec;
import com.adtek.sn.support.SnLocks;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.support.Tx;
import com.adtek.sn.support.Util;
import com.adtek.sn.vo.ExportFileVO;
import com.adtek.sn.vo.PageVO;
import com.adtek.sn.vo.PiBillVO;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * SN 导入（原系统 sn_import.py）：本系统之外已有的 SN 按导入模板导入号池，进入 SN 分配页（待分配）。先检查（不落库），再「确定导入」（PI
 * 操作锁与一个事务里重新检查，全部写入）；导入单的号全部待分配时可整单删除。
 */
@Service
public class SnImportServiceImpl implements SnImportService {
    public static final int MAX_MB = 20;
    public static final int MAX_BYTES = MAX_MB * 1024 * 1024;
    public static final int MAX_ROWS = 50_000;
    public static final int ISSUE_LIST_MAX = 500;
    static final int CHUNK = 1000;
    static final int SN_MAX = 64;
    static final int CODE_MAX = 64;
    static final int ORG_MAX = 128;
    static final int DEC_MAX = 19;
    static final String REQUIRED_MARK = "*";

    /** 模板列。 */
    public record Column(
            String field,
            String name,
            boolean required,
            List<String> aliases,
            int max,
            String hint,
            String example) {
        String title() {
            return required ? REQUIRED_MARK + name : name;
        }
    }

    private static List<Integer> bases() {
        return new ArrayList<>(new TreeSet<>(SnCodec.DEFAULT_CHARSETS.keySet()));
    }

    public static final List<Column> COLUMNS =
            List.of(
                    new Column("pi", "PI", true, List.of(), CODE_MAX, "生产订单的 PI，须与单据一致", "000001"),
                    new Column(
                            "bill",
                            "单据编号",
                            true,
                            List.of(),
                            CODE_MAX,
                            "金蝶生产订单编号，须在生产订单中",
                            "VT2026090001"),
                    new Column(
                            "attach",
                            "附件项次",
                            true,
                            List.of(),
                            CODE_MAX,
                            "须与该单据该行的附件项次相同（该行没有附件项次时不能导入，可先在 SN 生成页补填）",
                            "A01"),
                    new Column(
                            "material",
                            "物料编码",
                            true,
                            List.of("物料编号"),
                            CODE_MAX,
                            "须与该行的物料编码相同（列名也可写作「物料编号」）",
                            "9148363"),
                    new Column(
                            "seqIn",
                            "ERP行号",
                            true,
                            List.of("行号", "单据行号"),
                            CODE_MAX,
                            "该单据在金蝶里的明细行号，正整数（列名也可写作「行号」「单据行号」）",
                            "1"),
                    new Column(
                            "sn",
                            "完整 SN",
                            true,
                            List.of("SN"),
                            SN_MAX,
                            "不含空格，最长 " + SN_MAX + " 位；不能与系统里已有的号、文件里其他行重复（列名也可写作「SN」）",
                            "HX100000001A"),
                    new Column(
                            "seqLenIn",
                            "流水位数",
                            true,
                            List.of(),
                            CODE_MAX,
                            "1～" + SnCodec.MAX_SEQ_LEN + "，等于流水号的位数",
                            "6"),
                    new Column(
                            "baseIn", "进制位数", true, List.of(), CODE_MAX, "10 / 16 / 32 / 36", "10"),
                    new Column(
                            "serial",
                            "流水号",
                            false,
                            List.of(),
                            SnCodec.MAX_SEQ_LEN,
                            "选填；不填时按十进制流水号、进制位数、流水位数换算（补前导 0）。填了须按进制位数书写，位数等于流水位数，"
                                    + "保留前导 0，字母大写，32 进制不用 I、L、O、U，且与十进制流水号换算的相同",
                            "000001"),
                    new Column(
                            "decIn",
                            "十进制流水号",
                            true,
                            List.of("十进制流水"),
                            DEC_MAX,
                            "非负整数（十进制）；须在进制位数、流水位数能表示的范围内，流水号按它换算",
                            "1"),
                    new Column(
                            "length",
                            "米数",
                            false,
                            List.of(),
                            CODE_MAX,
                            "选填；填了须与该行的米数相同，原样保存",
                            "0100"),
                    new Column("unit", "产品单位", false, List.of(), CODE_MAX, "选填", "M"),
                    new Column(
                            "factoryIn",
                            "生产组织编号",
                            false,
                            List.of(),
                            CODE_MAX,
                            "选填；填了须与单据的生产组织编码相同",
                            "110"),
                    new Column(
                            "orgName", "生产组织", true, List.of(), ORG_MAX, "须与单据的生产组织名称相同", "越南工厂"));

    public static final List<String> HEADERS = COLUMNS.stream().map(Column::name).toList();
    static final List<String> REQUIRED =
            COLUMNS.stream().filter(Column::required).map(Column::name).toList();
    static final List<String> OPTIONAL =
            COLUMNS.stream().filter(c -> !c.required()).map(Column::name).toList();
    private static final Set<String> NUMERIC = Set.of("seqIn", "seqLenIn", "baseIn", "decIn");
    private static final Map<String, Column> BY_KEY = new HashMap<>();

    static {
        for (Column c : COLUMNS) {
            BY_KEY.put(key(c.name()), c);
            for (String a : c.aliases()) {
                BY_KEY.put(key(a), c);
            }
        }
    }

    private static final List<String> GUIDE_NOTES =
            List.of(
                    "说明：",
                    "1. 在「SN导入」表填写，一行一枚号；表头列名不要改（列名前的 * 表示必填，保留或去掉都可以），列的顺序可以调整，" + "多出的其他列不读。",
                    "2. 标 * 的列必须有，且每行都要填；选填的列可以整列不要。",
                    "3. 各列已设为文本格式，流水号、米数的前导 0 会原样保留，请不要改成数字格式（十进制流水号较长时也请保持文本，"
                            + "Excel 数字格式超过 15 位会丢精度）。",
                    "4. 流水号可以不填，系统按十进制流水号、进制位数、流水位数换算；填了须与十进制流水号换算的一致。",
                    "5. PI、单据编号、ERP行号、物料编码、附件项次、生产组织须与该单据（及该行）一致；"
                            + "该单据在系统中已有生成或已分配的 SN 时不能导入（只有导入的、还待分配的号时可以继续导入）。",
                    "6. 导入时先检查，逐行列出不能导入的原因；有一行不能导入，整个文件都不导入。");

    /** 文件里的一行（各列为去首尾空白的字符串，空串 = 没填）及检查时算出的值；字段供 Mapper 读取。 */
    public static final class ImportRow {
        public int rowNo;
        public String pi = "";
        public String bill = "";
        public String seqIn = "";
        public String material = "";
        public String sn = "";
        public String seqLenIn = "";
        public String baseIn = "";
        public String serial = "";
        public String decIn = "";
        public String attach = "";
        public String length = "";
        public String unit = "";
        public String factoryIn = "";
        public String orgName = "";
        public String customer = "";
        public Integer seq;
        public Integer seqLen;
        public Integer base;
        public Long seqDec;
        public String factory = "";
        public Long importId;
        public final List<Map<String, Object>> issues = new ArrayList<>();

        public String getLengthOrNull() {
            return length.isEmpty() ? null : length;
        }

        String get(String field) {
            return switch (field) {
                case "pi" -> pi;
                case "bill" -> bill;
                case "seqIn" -> seqIn;
                case "material" -> material;
                case "sn" -> sn;
                case "seqLenIn" -> seqLenIn;
                case "baseIn" -> baseIn;
                case "serial" -> serial;
                case "decIn" -> decIn;
                case "attach" -> attach;
                case "length" -> length;
                case "unit" -> unit;
                case "factoryIn" -> factoryIn;
                case "orgName" -> orgName;
                default -> "";
            };
        }

        void set(String field, String v) {
            switch (field) {
                case "pi" -> pi = v;
                case "bill" -> bill = v;
                case "seqIn" -> seqIn = v;
                case "material" -> material = v;
                case "sn" -> sn = v;
                case "seqLenIn" -> seqLenIn = v;
                case "baseIn" -> baseIn = v;
                case "serial" -> serial = v;
                case "decIn" -> decIn = v;
                case "attach" -> attach = v;
                case "length" -> length = v;
                case "unit" -> unit = v;
                case "factoryIn" -> factoryIn = v;
                case "orgName" -> orgName = v;
                default -> {}
            }
        }

        void issue(String code, Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("params", R.of(kv));
            issues.add(m);
        }
    }

    private final SnImportMapper mapper;
    private final OrderServiceImpl orders;
    private final ChangeServiceImpl changes;
    private final GenerateServiceImpl generate;
    private final BillStatsServiceImpl billStats;
    private final AuditServiceImpl audit;
    private final SnLocks locks;
    private final SnSettings settings;
    private final Tx tx;

    public SnImportServiceImpl(
            SnImportMapper mapper,
            OrderServiceImpl orders,
            ChangeServiceImpl changes,
            GenerateServiceImpl generate,
            BillStatsServiceImpl billStats,
            AuditServiceImpl audit,
            SnLocks locks,
            SnSettings settings,
            Tx tx) {
        this.mapper = mapper;
        this.orders = orders;
        this.changes = changes;
        this.generate = generate;
        this.billStats = billStats;
        this.audit = audit;
        this.locks = locks;
        this.settings = settings;
        this.tx = tx;
    }

    private CurrentUser cu() {
        return CurrentUser.current(settings.initAdminEmpNo()).requireOperator();
    }

    // ---------------------------------------------------------------- 模板
    @Override
    public ExportFileVO template() {
        cu();
        return new ExportFileVO(
                "SN导入模板",
                out -> {
                    try (XSSFWorkbook wb = new XSSFWorkbook()) {
                        XSSFSheet ws = wb.createSheet("SN导入");
                        XSSFCellStyle text = wb.createCellStyle();
                        text.setDataFormat(wb.createDataFormat().getFormat("@"));
                        Row head = ws.createRow(0);
                        XSSFDrawing drawing = ws.createDrawingPatriarch();
                        for (int i = 0; i < COLUMNS.size(); i++) {
                            Column c = COLUMNS.get(i);
                            int width =
                                    "sn".equals(c.field())
                                            ? 34
                                            : NUMERIC.contains(c.field()) ? 12 : 16;
                            ws.setColumnWidth(i, width * 256);
                            ws.setDefaultColumnStyle(i, text);
                            XSSFCellStyle st = wb.createCellStyle();
                            XSSFFont font = wb.createFont();
                            font.setBold(true);
                            font.setColor(
                                    new XSSFColor(hex(c.required() ? "C00000" : "595959"), null));
                            st.setFont(font);
                            st.setFillForegroundColor(
                                    new XSSFColor(hex(c.required() ? "FDE9E7" : "F2F2F2"), null));
                            st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                            st.setDataFormat(text.getDataFormat());
                            Cell cell = head.createCell(i);
                            cell.setCellValue(c.title());
                            cell.setCellStyle(st);
                            ClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, i, 0, i + 3, 5);
                            var comment = drawing.createCellComment(anchor);
                            comment.setString(
                                    new XSSFRichTextString(
                                            (c.required() ? "必填" : "选填")
                                                    + "："
                                                    + c.hint()
                                                    + "\n示例："
                                                    + c.example()));
                            comment.setAuthor("SN 中台");
                            cell.setCellComment(comment);
                        }
                        ws.createFreezePane(0, 1);
                        XSSFSheet guide = wb.createSheet("填写说明");
                        XSSFFont bold = wb.createFont();
                        bold.setBold(true);
                        XSSFCellStyle boldStyle = wb.createCellStyle();
                        boldStyle.setFont(bold);
                        XSSFFont red = wb.createFont();
                        red.setBold(true);
                        red.setColor(new XSSFColor(hex("C00000"), null));
                        XSSFCellStyle redStyle = wb.createCellStyle();
                        redStyle.setFont(red);
                        int r = 0;
                        Row g = guide.createRow(r++);
                        String[] gh = {"列名", "是否必填", "填写要求", "示例"};
                        for (int i = 0; i < gh.length; i++) {
                            Cell cell = g.createCell(i);
                            cell.setCellValue(gh[i]);
                            cell.setCellStyle(boldStyle);
                        }
                        for (Column c : COLUMNS) {
                            Row x = guide.createRow(r++);
                            x.createCell(0).setCellValue(c.name());
                            Cell req = x.createCell(1);
                            req.setCellValue(c.required() ? "必填" : "选填");
                            if (c.required()) {
                                req.setCellStyle(redStyle);
                            }
                            x.createCell(2).setCellValue(c.hint());
                            x.createCell(3).setCellValue(c.example());
                        }
                        r++;
                        for (String line : GUIDE_NOTES) {
                            Row x = guide.createRow(r);
                            x.createCell(0).setCellValue(line);
                            guide.addMergedRegion(new CellRangeAddress(r, r, 0, 3));
                            r++;
                        }
                        int[] widths = {16, 10, 70, 18};
                        for (int i = 0; i < widths.length; i++) {
                            guide.setColumnWidth(i, widths[i] * 256);
                        }
                        wb.write(out);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    private static byte[] hex(String rgb) {
        return new byte[] {
            (byte) Integer.parseInt(rgb.substring(0, 2), 16),
            (byte) Integer.parseInt(rgb.substring(2, 4), 16),
            (byte) Integer.parseInt(rgb.substring(4, 6), 16)
        };
    }

    // ---------------------------------------------------------------- 解析
    private static final DateTimeFormatter PY_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单元格 → 去首尾空白的字符串；整数值的数字去掉 .0。 */
    static String text(Cell c) {
        if (c == null) {
            return "";
        }
        CellType t =
                c.getCellType() == CellType.FORMULA
                        ? c.getCachedFormulaResultType()
                        : c.getCellType();
        return switch (t) {
            case STRING -> c.getStringCellValue().strip();
            case BOOLEAN -> c.getBooleanCellValue() ? "TRUE" : "FALSE";
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(c)) {
                    LocalDateTime d = c.getLocalDateTimeCellValue();
                    yield d == null ? "" : PY_DT.format(d);
                }
                double v = c.getNumericCellValue();
                if (v == Math.rint(v) && !Double.isInfinite(v)) {
                    yield new BigDecimal(v).toBigInteger().toString();
                }
                yield Double.toString(v);
            }
            default -> "";
        };
    }

    /** 表头比较用：去掉空白与必填标记 *（含全角＊），不分大小写。 */
    static String key(String v) {
        String s = v.replaceAll("\\s+", "").toLowerCase();
        int a = 0;
        int b = s.length();
        while (a < b && (s.charAt(a) == '*' || s.charAt(a) == '＊')) {
            a++;
        }
        while (b > a && (s.charAt(b - 1) == '*' || s.charAt(b - 1) == '＊')) {
            b--;
        }
        return s.substring(a, b);
    }

    private static RuntimeException headerError(List<String> missing) {
        return Biz.err(
                SnErrorCode.IMPORT_HEADER_INVALID,
                "missing",
                String.join("、", missing),
                "required",
                String.join("、", REQUIRED),
                "optional",
                String.join("、", OPTIONAL));
    }

    private static Map<String, Integer> checkHeader(List<String> cells) {
        Map<String, Integer> at = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            Column c = BY_KEY.get(key(cells.get(i)));
            if (c == null) {
                continue;
            }
            if (at.containsKey(c.field())) {
                throw Biz.err(SnErrorCode.IMPORT_HEADER_DUPLICATE, "column", c.name());
            }
            at.put(c.field(), i);
        }
        List<String> missing =
                COLUMNS.stream()
                        .filter(c -> c.required() && !at.containsKey(c.field()))
                        .map(Column::name)
                        .toList();
        if (!missing.isEmpty()) {
            throw headerError(missing);
        }
        return at;
    }

    List<ImportRow> parse(byte[] content) {
        if (content == null || content.length == 0) {
            throw Biz.err(SnErrorCode.IMPORT_EMPTY);
        }
        if (content.length > MAX_BYTES) {
            throw Biz.err(SnErrorCode.IMPORT_FILE_TOO_LARGE, "max", MAX_MB);
        }
        XSSFWorkbook wb;
        try {
            wb = new XSSFWorkbook(new ByteArrayInputStream(content));
        } catch (Exception e) {
            throw Biz.err(SnErrorCode.IMPORT_FILE_INVALID);
        }
        try (wb) {
            if (wb.getNumberOfSheets() == 0) {
                throw Biz.err(SnErrorCode.IMPORT_EMPTY);
            }
            Sheet ws = wb.getSheetAt(0);
            Map<String, Integer> at = null;
            List<ImportRow> out = new ArrayList<>();
            int last = ws.getLastRowNum();
            for (int rn = 0; rn <= last; rn++) {
                Row row = ws.getRow(rn);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int ci = 0; ci < Math.max(0, row.getLastCellNum()); ci++) {
                        cells.add(text(row.getCell(ci)));
                    }
                }
                if (at == null) {
                    if (cells.stream().anyMatch(v -> !v.isEmpty())) {
                        at = checkHeader(cells);
                    }
                    continue;
                }
                ImportRow r = new ImportRow();
                r.rowNo = rn + 1;
                boolean any = false;
                for (Map.Entry<String, Integer> e : at.entrySet()) {
                    String v = e.getValue() < cells.size() ? cells.get(e.getValue()) : "";
                    r.set(e.getKey(), v);
                    any |= !v.isEmpty();
                }
                if (!any) {
                    continue;
                }
                out.add(r);
                if (out.size() > MAX_ROWS) {
                    throw Biz.err(
                            SnErrorCode.IMPORT_TOO_MANY, "max", MAX_ROWS, "rows", MAX_ROWS + "+");
                }
            }
            if (at == null) {
                throw headerError(REQUIRED);
            }
            if (out.isEmpty()) {
                throw Biz.err(SnErrorCode.IMPORT_EMPTY);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- 检查
    private static Integer positive(String v) {
        if (v.isEmpty() || !v.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            BigInteger n = new BigInteger(v);
            if (n.signum() <= 0 || n.bitLength() > 31) {
                return n.signum() <= 0 ? null : Integer.MAX_VALUE;
            }
            return n.intValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void checkValues(ImportRow r, Map<String, Integer> first) {
        for (Column c : COLUMNS) {
            String v = r.get(c.field());
            if (v.isEmpty()) {
                if (c.required()) {
                    r.issue("EMPTY", "column", c.field());
                }
            } else if (v.codePointCount(0, v.length()) > c.max()) {
                r.issue("TOO_LONG", "column", c.field(), "max", c.max());
            }
        }
        if (!r.sn.isEmpty() && r.sn.codePointCount(0, r.sn.length()) <= SN_MAX) {
            if (r.sn.chars().anyMatch(Character::isWhitespace)) {
                r.issue("SN_SPACE");
            }
            if (first.containsKey(r.sn)) {
                r.issue("FILE_DUP", "row", first.get(r.sn));
            } else {
                first.put(r.sn, r.rowNo);
            }
        }
        if (!r.seqIn.isEmpty()) {
            r.seq = positive(r.seqIn);
            if (r.seq == null) {
                r.issue("SEQ_INVALID");
            }
        }
        List<Integer> bases = bases();
        if (!r.baseIn.isEmpty()) {
            r.base = positive(r.baseIn);
            if (r.base == null || !bases.contains(r.base)) {
                r.base = null;
                r.issue(
                        "BASE_INVALID",
                        "bases",
                        String.join(" / ", bases.stream().map(String::valueOf).toList()));
            }
        }
        if (!r.seqLenIn.isEmpty()) {
            r.seqLen = positive(r.seqLenIn);
            if (r.seqLen == null || r.seqLen > SnCodec.MAX_SEQ_LEN) {
                r.seqLen = null;
                r.issue("SEQ_LEN_INVALID", "max", SnCodec.MAX_SEQ_LEN);
            }
        }
        BigInteger dec = null;
        if (!r.decIn.isEmpty() && r.decIn.length() <= DEC_MAX) {
            if (r.decIn.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
                dec = new BigInteger(r.decIn);
            } else {
                r.issue("DEC_INVALID");
            }
        }
        if (!r.serial.isEmpty()) {
            if (r.seqLen != null && r.serial.length() != r.seqLen) {
                r.issue("SERIAL_LEN_MISMATCH", "len", r.seqLen);
            } else if (r.base != null && r.seqLen != null) {
                String chars = SnCodec.DEFAULT_CHARSETS.get(r.base);
                boolean bad = r.serial.chars().anyMatch(ch -> chars.indexOf(ch) < 0);
                if (bad) {
                    r.issue("SERIAL_CHAR_INVALID", "base", r.base, "chars", chars);
                } else if (dec != null) {
                    BigInteger v = BigInteger.ZERO;
                    BigInteger b = BigInteger.valueOf(r.base);
                    for (int i = 0; i < r.serial.length(); i++) {
                        v =
                                v.multiply(b)
                                        .add(BigInteger.valueOf(chars.indexOf(r.serial.charAt(i))));
                    }
                    if (!v.equals(dec)) {
                        r.issue("SERIAL_DEC_MISMATCH", "serial", r.serial, "dec", v.toString());
                    } else {
                        r.seqDec = dec.longValueExact();
                    }
                }
            }
        } else if (dec != null && r.base != null && r.seqLen != null) {
            BigInteger top = BigInteger.valueOf(r.base).pow(r.seqLen).subtract(BigInteger.ONE);
            if (dec.compareTo(top) > 0) {
                r.issue("DEC_OVERFLOW", "base", r.base, "len", r.seqLen, "max", top.toString());
            } else {
                r.seqDec = dec.longValueExact();
                r.serial = encode(dec, r.base, r.seqLen);
            }
        }
    }

    static String encode(BigInteger dec, int base, int n) {
        String chars = SnCodec.DEFAULT_CHARSETS.get(base);
        char[] out = new char[n];
        BigInteger b = BigInteger.valueOf(base);
        BigInteger v = dec;
        for (int i = n - 1; i >= 0; i--) {
            BigInteger[] qr = v.divideAndRemainder(b);
            out[i] = chars.charAt(qr[1].intValue());
            v = qr[0];
        }
        return new String(out);
    }

    private void checkOrder(
            CurrentUser cu,
            ImportRow r,
            Map<String, Object> b,
            Map<String, Object> line,
            Map<String, Long> used) {
        r.factory = R.sz(b, "org_code");
        r.customer = R.sz(b, "customer_code");
        if (!r.pi.isEmpty() && !r.pi.equals(R.s(b, "pi"))) {
            r.issue("PI_MISMATCH", "bill_no", r.bill, "pi", R.s(b, "pi"));
        }
        String fname = R.s(b, "factory_name");
        String oname = R.s(b, "org_name");
        String name = fname != null && !fname.isEmpty() ? fname : (oname == null ? "" : oname);
        String orgText = (r.factory + " " + name).strip();
        Set<String> names = new HashSet<>();
        if (oname != null && !oname.isEmpty()) {
            names.add(oname);
        }
        if (fname != null && !fname.isEmpty()) {
            names.add(fname);
        }
        if (!r.factoryIn.isEmpty() && !r.factoryIn.equals(r.factory)) {
            r.issue("ORG_MISMATCH", "bill_no", r.bill, "factory", orgText);
        } else if (!r.orgName.isEmpty() && !names.contains(r.orgName)) {
            if (!r.factoryIn.isEmpty()) {
                r.issue(
                        "ORG_NAME_MISMATCH",
                        "factory",
                        r.factory,
                        "name",
                        name.isEmpty() ? "—" : name);
            } else {
                r.issue("ORG_MISMATCH", "bill_no", r.bill, "factory", orgText);
            }
        }
        if (!cu.isAdmin() && !cu.factories().contains(r.factory)) {
            r.issue("FACTORY_FORBIDDEN", "factory", r.factory);
        }
        long n = used.getOrDefault(r.bill, 0L);
        if (n > 0) {
            r.issue("BILL_HAS_SN", "bill_no", r.bill, "count", n);
        }
        if (r.seq == null) {
            return;
        }
        if (line == null) {
            r.issue("LINE_NOT_FOUND", "bill_no", r.bill, "seq", r.seq);
            return;
        }
        String mat = R.sz(line, "material_code");
        if (!r.material.isEmpty() && !r.material.equals(mat)) {
            r.issue(
                    "MATERIAL_MISMATCH",
                    "bill_no",
                    r.bill,
                    "seq",
                    r.seq,
                    "material",
                    mat.isEmpty() ? "—" : mat);
        }
        String effAttach = R.sz(line, "eff_attach");
        if (!r.attach.isEmpty() && !r.attach.equals(effAttach)) {
            if (!effAttach.isEmpty()) {
                r.issue("ATTACH_MISMATCH", "bill_no", r.bill, "seq", r.seq, "attach", effAttach);
            } else {
                r.issue("LINE_NO_ATTACH", "bill_no", r.bill, "seq", r.seq);
            }
        }
        String effLength = R.sz(line, "eff_length");
        if (!r.length.isEmpty() && !r.length.equals(effLength)) {
            r.issue(
                    "LENGTH_MISMATCH",
                    "bill_no",
                    r.bill,
                    "seq",
                    r.seq,
                    "length",
                    effLength.isEmpty() ? "—" : effLength);
        }
    }

    private void validate(CurrentUser cu, List<ImportRow> rows, boolean lock) {
        Map<String, Integer> first = new LinkedHashMap<>();
        for (ImportRow r : rows) {
            checkValues(r, first);
        }
        TreeSet<String> bills = new TreeSet<>();
        for (ImportRow r : rows) {
            if (!r.bill.isEmpty() && r.bill.length() <= CODE_MAX) {
                bills.add(r.bill);
            }
        }
        Map<String, Map<String, Object>> heads = new HashMap<>();
        Map<String, Map<String, Object>> lines = new HashMap<>();
        for (List<String> part : R.chunks(bills, CHUNK)) {
            for (Map<String, Object> b : mapper.heads(part)) {
                heads.put(R.s(b, "bill_no"), b);
            }
            for (Map<String, Object> x : orders.effectiveLines(mapper.lines(part))) {
                lines.put(R.key(R.s(x, "bill_no"), R.i(x, "seq")), x);
            }
        }
        Map<String, Long> used = new HashMap<>();
        for (List<String> part : R.chunks(new TreeSet<>(heads.keySet()), CHUNK)) {
            for (Map<String, Object> x : mapper.usedBills(part)) {
                used.put(R.s(x, "bill_no"), R.l(x, "n"));
            }
        }
        for (ImportRow r : rows) {
            if (r.bill.isEmpty() || r.bill.length() > CODE_MAX) {
                continue;
            }
            Map<String, Object> b = heads.get(r.bill);
            if (b == null) {
                r.issue("BILL_NOT_FOUND", "bill_no", r.bill);
                continue;
            }
            checkOrder(cu, r, b, r.seq == null ? null : lines.get(R.key(r.bill, r.seq)), used);
        }
        Map<String, String> pool = new HashMap<>();
        Map<String, String> done = new HashMap<>();
        for (List<String> part : R.chunks(new TreeSet<>(first.keySet()), CHUNK)) {
            for (Map<String, Object> x : mapper.poolSns(part, lock)) {
                pool.put(R.s(x, "sn"), R.s(x, "pi_no"));
            }
            for (Map<String, Object> x : mapper.importedSns(part, lock)) {
                done.put(R.s(x, "sn"), R.s(x, "import_no"));
            }
        }
        for (ImportRow r : rows) {
            if (done.containsKey(r.sn)) {
                r.issue("IMPORTED", "import_no", done.get(r.sn));
            } else if (pool.containsKey(r.sn)) {
                r.issue("IN_POOL", "pi", pool.get(r.sn));
            }
        }
    }

    private static List<Map<String, Object>> issueRows(List<ImportRow> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ImportRow r : rows) {
            if (r.issues.isEmpty()) {
                continue;
            }
            out.add(
                    R.of(
                            "row", r.rowNo, "pi", r.pi, "billNo", r.bill, "sn", r.sn, "issues",
                            r.issues));
            if (out.size() >= ISSUE_LIST_MAX) {
                break;
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> check(byte[] content) {
        CurrentUser cu = cu();
        List<ImportRow> rows = parse(content);
        validate(cu, rows, false);
        long bad = rows.stream().filter(r -> !r.issues.isEmpty()).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("ok", rows.size() - bad);
        out.put("bad", bad);
        out.put("issues", issueRows(rows));
        out.put("pis", rows.stream().map(r -> r.pi).filter(s -> !s.isEmpty()).distinct().count());
        out.put(
                "bills",
                rows.stream().map(r -> r.bill).filter(s -> !s.isEmpty()).distinct().count());
        out.put("importable", bad == 0);
        return out;
    }

    // ---------------------------------------------------------------- 导入
    @Override
    public Map<String, Object> run(byte[] content, String fileNameIn) {
        CurrentUser cu = cu();
        if (!(cu.isAdmin() || cu.isOperator())) {
            throw Biz.err(SnErrorCode.FORBIDDEN);
        }
        List<ImportRow> rows = parse(content);
        String fn = Util.cut(Util.trim(fileNameIn), 255);
        String fileName = fn == null ? "" : fn;
        String[] pis =
                rows.stream()
                        .map(r -> r.pi)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .sorted()
                        .toArray(String[]::new);
        return locks.withPiLock(
                () ->
                        tx.run(
                                () -> {
                                    validate(cu, rows, true);
                                    long bad =
                                            rows.stream().filter(r -> !r.issues.isEmpty()).count();
                                    if (bad > 0) {
                                        throw Biz.err(SnErrorCode.IMPORT_HAS_ISSUES, "count", bad);
                                    }
                                    LocalDateTime now = Util.now();
                                    String no = Util.nextId("IM");
                                    for (List<ImportRow> part : R.chunks(rows, CHUNK)) {
                                        List<String> sns = part.stream().map(r -> r.sn).toList();
                                        try {
                                            mapper.insertImports(
                                                    no, part, fileName, cu.empNo(), now);
                                            Map<String, Long> ids = new HashMap<>();
                                            for (Map<String, Object> x :
                                                    mapper.importIds(no, sns)) {
                                                ids.put(R.s(x, "sn"), R.l(x, "id"));
                                            }
                                            for (ImportRow r : part) {
                                                r.importId = ids.get(r.sn);
                                            }
                                            mapper.insertItems(no, part, now);
                                            generate.continueDeletedRev(sns);
                                            billStats.touchMany(
                                                    part.stream()
                                                            .map(r -> new PiBillVO(r.pi, r.bill))
                                                            .toList());
                                        } catch (RuntimeException ex) {
                                            if (!DbErrors.isDuplicate(ex)) {
                                                throw ex;
                                            }
                                            String hit = mapper.firstPoolSn(sns);
                                            throw Biz.err(
                                                    SnErrorCode.IMPORT_CONFLICT,
                                                    "sn",
                                                    hit != null ? hit : part.get(0).sn);
                                        }
                                    }
                                    TreeSet<String> bills = new TreeSet<>();
                                    rows.forEach(r -> bills.add(r.bill));
                                    for (String b : bills) {
                                        changes.ensureLedger(b);
                                    }
                                    changes.refreshItems(bills, false);
                                    TreeMap<String, List<ImportRow>> byBill = new TreeMap<>();
                                    for (ImportRow r : rows) {
                                        byBill.computeIfAbsent(
                                                        r.pi + "\u0000" + r.bill,
                                                        k -> new ArrayList<>())
                                                .add(r);
                                    }
                                    for (List<ImportRow> rs : byBill.values()) {
                                        List<String> ss =
                                                rs.stream().map(r -> r.sn).sorted().toList();
                                        audit.record(
                                                cu,
                                                AuditEntryDTO.of(AuditAction.SN_IMPORT)
                                                        .factory(
                                                                rs.get(0).factory.isEmpty()
                                                                        ? null
                                                                        : rs.get(0).factory)
                                                        .pi(rs.get(0).pi)
                                                        .bill(rs.get(0).bill)
                                                        .range(ss.get(0), ss.get(ss.size() - 1))
                                                        .count(rs.size())
                                                        .status(null, Util.PENDING_ALLOC)
                                                        .batch(no)
                                                        .info(
                                                                "file="
                                                                        + fileName
                                                                        + " rows="
                                                                        + rs.get(0).rowNo
                                                                        + ".."
                                                                        + rs.get(rs.size() - 1)
                                                                                .rowNo));
                                    }
                                    Map<String, Object> out = new LinkedHashMap<>();
                                    out.put("importNo", no);
                                    out.put("qty", rows.size());
                                    out.put("pis", rows.stream().map(r -> r.pi).distinct().count());
                                    out.put("bills", byBill.size());
                                    return out;
                                }),
                pis);
    }

    // ---------------------------------------------------------------- 列表
    @Override
    public PageVO<Map<String, Object>> items(
            String pi, String billNo, String sn, String importNo, PageReq page) {
        CurrentUser cu = cu();
        List<String> facs = cu.readFactories(null);
        String p = Util.trimOrNull(pi);
        String b = Util.trimOrNull(billNo);
        String n = Util.trimOrNull(importNo);
        String s = Util.trim(sn).isEmpty() ? null : Util.likePrefix(Util.trim(sn));
        long total = mapper.countItems(facs, p, b, n, s);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r :
                mapper.pageItems(facs, p, b, n, s, page.offset(), page.size())) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k :
                    List.of(
                            "id",
                            "import_no",
                            "sn",
                            "pi_no",
                            "bill_no",
                            "bill_seq",
                            "cur_bill_seq",
                            "material_code",
                            "attach_no",
                            "serial_text",
                            "seq_base",
                            "seq_len",
                            "unit",
                            "customer_code",
                            "factory_code",
                            "file_name",
                            "row_no",
                            "created_by",
                            "deleted_by")) {
                m.put(com.adtek.sn.support.Camel.key(k), r.get(k));
            }
            m.put("length", R.sz(r, "length_text"));
            m.put("createdAt", R.t(r, "created_at"));
            m.put("deletedAt", R.t(r, "deleted_at"));
            m.put("status", R.s(r, "status"));
            m.put("allocatedTo", R.s(r, "cur_factory"));
            m.put("curPi", R.s(r, "cur_pi"));
            m.put("curBill", R.s(r, "cur_bill"));
            out.add(m);
        }
        return PageVO.of(out, total, page.page(), page.size());
    }

    /** 导入记录 id（号池里导入的号的 seq_dec）→ 行号、当前行号、流水号、进制、流水位数、十进制流水、产品单位、PI。 */
    public Map<Long, Map<String, Object>> details(Collection<Long> ids) {
        Map<Long, Map<String, Object>> out = new HashMap<>();
        for (List<Long> part : R.chunks(new TreeSet<>(ids), CHUNK)) {
            for (Map<String, Object> r : mapper.details(part)) {
                out.put(R.l(r, "id"), r);
            }
        }
        return out;
    }

    /** 号池行里 SN 导入的号带上导入记录（键 import）；生成的号不变，没有导入的号时不查库。 */
    public List<Map<String, Object>> withDetails(List<Map<String, Object>> rows) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (R.sz(r, "seq_pi_no").isEmpty()) {
                ids.add(R.l(r, "seq_dec"));
            }
        }
        if (!ids.isEmpty()) {
            Map<Long, Map<String, Object>> d = details(ids);
            for (Map<String, Object> r : rows) {
                if (R.sz(r, "seq_pi_no").isEmpty()) {
                    r.put("import", d.get(R.l(r, "seq_dec")));
                }
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------- 整单删除
    private record Records(String no, List<Map<String, Object>> recs) {}

    private Records records(CurrentUser cu, String noIn) {
        String no = Util.trim(noIn);
        List<Map<String, Object>> recs = no.isEmpty() ? List.of() : mapper.records(no);
        if (recs.isEmpty()) {
            throw Biz.err(SnErrorCode.IMPORT_NOT_FOUND, "import_no", no);
        }
        cu.checkWrite(
                recs.stream().map(r -> R.s(r, "factory_code")).distinct().toArray(String[]::new));
        return new Records(no, recs);
    }

    private List<Map<String, Object>> pool(String no, List<String> sns, boolean lock) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<String> part : R.chunks(sns, CHUNK)) {
            out.addAll(mapper.pool(no, part, lock));
        }
        return out;
    }

    @Override
    public Map<String, Object> summary(String noIn) {
        CurrentUser cu = cu();
        Records rs = records(cu, noIn);
        List<Map<String, Object>> items =
                pool(rs.no(), rs.recs().stream().map(r -> R.s(r, "sn")).toList(), false);
        long pending =
                items.stream().filter(x -> Util.PENDING_ALLOC.equals(R.s(x, "status"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("importNo", rs.no());
        out.put("qty", rs.recs().size());
        out.put("pending", pending);
        out.put("allocated", items.size() - pending);
        out.put(
                "pis",
                new ArrayList<>(
                        new TreeSet<>(rs.recs().stream().map(r -> R.s(r, "pi_no")).toList())));
        out.put(
                "bills",
                new ArrayList<>(
                        new TreeSet<>(rs.recs().stream().map(r -> R.s(r, "bill_no")).toList())));
        out.put("deletable", items.size() - pending == 0);
        return out;
    }

    @Override
    public Map<String, Object> delete(String noIn) {
        CurrentUser cu = cu();
        Records rs0 = records(cu, noIn);
        List<String> sns0 = rs0.recs().stream().map(r -> R.s(r, "sn")).toList();
        TreeSet<String> pis = new TreeSet<>();
        pool(rs0.no(), sns0, false).forEach(x -> pis.add(R.s(x, "pi_no")));
        rs0.recs().forEach(r -> pis.add(R.s(r, "pi_no")));
        return locks.withPiLock(
                () ->
                        tx.run(
                                () -> {
                                    Records rs = records(cu, rs0.no());
                                    String no = rs.no();
                                    List<Map<String, Object>> items =
                                            pool(
                                                    no,
                                                    rs.recs().stream()
                                                            .map(r -> R.s(r, "sn"))
                                                            .toList(),
                                                    true);
                                    for (Map<String, Object> x : items) {
                                        if (!pis.contains(R.s(x, "pi_no"))) {
                                            throw Biz.err(SnErrorCode.CONFLICT_RETRY);
                                        }
                                    }
                                    long allocated =
                                            items.stream()
                                                    .filter(
                                                            x ->
                                                                    !Util.PENDING_ALLOC.equals(
                                                                            R.s(x, "status")))
                                                    .count();
                                    if (allocated > 0) {
                                        throw Biz.err(
                                                SnErrorCode.IMPORT_DELETE_ALLOCATED,
                                                "import_no",
                                                no,
                                                "count",
                                                allocated);
                                    }
                                    billStats.touchMany(
                                            items.stream()
                                                    .map(
                                                            x ->
                                                                    new PiBillVO(
                                                                            R.s(x, "pi_no"),
                                                                            R.s(x, "bill_no")))
                                                    .toList());
                                    List<Long> ids = items.stream().map(x -> R.l(x, "id")).toList();
                                    for (List<Long> part : R.chunks(ids, CHUNK)) {
                                        mapper.deleteItems(part);
                                    }
                                    mapper.markDeleted(no, cu.empNo(), Util.now());
                                    TreeMap<String, List<Map<String, Object>>> byBill =
                                            new TreeMap<>();
                                    for (Map<String, Object> x : items) {
                                        byBill.computeIfAbsent(
                                                        R.s(x, "pi_no")
                                                                + "\u0000"
                                                                + R.s(x, "bill_no"),
                                                        k -> new ArrayList<>())
                                                .add(x);
                                    }
                                    for (List<Map<String, Object>> xs : byBill.values()) {
                                        List<String> ss =
                                                xs.stream()
                                                        .map(x -> R.s(x, "sn"))
                                                        .sorted()
                                                        .toList();
                                        String gen = R.s(xs.get(0), "gen_org_code");
                                        audit.record(
                                                cu,
                                                AuditEntryDTO.of(AuditAction.SN_IMPORT_DELETE)
                                                        .factory(
                                                                gen == null || gen.isEmpty()
                                                                        ? null
                                                                        : gen)
                                                        .pi(R.s(xs.get(0), "pi_no"))
                                                        .bill(R.s(xs.get(0), "bill_no"))
                                                        .range(ss.get(0), ss.get(ss.size() - 1))
                                                        .count(xs.size())
                                                        .status(Util.PENDING_ALLOC, null)
                                                        .batch(no));
                                    }
                                    Map<String, Object> out = new LinkedHashMap<>();
                                    out.put("importNo", no);
                                    out.put("qty", items.size());
                                    out.put("bills", byBill.size());
                                    return out;
                                }),
                pis.toArray(String[]::new));
    }
}
