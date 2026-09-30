package com.adtek.integration.legacy;

import com.adtek.sn.vo.LegacySnapshotManifestVO.Issue;
import java.sql.*;
import java.util.*;

/** 仓库V1的固定只读检查；只输出计数/类别，原行及口令哈希不进入控制台或报告。 */
final class LegacyPreflightChecks {
    private LegacyPreflightChecks(){}
    private record Check(String code,String table,String sql,String disposition){}
    static List<Issue> check(Connection c)throws SQLException{
        var checks=List.of(
            block("SN_KEY_MISSING","sn_item","SELECT COUNT(*) FROM sn_item i LEFT JOIN sn_key k ON k.pi_no=i.pi_no AND k.sn=i.sn WHERE k.sn IS NULL"),
            block("SN_KEY_ORPHAN","sn_key","SELECT COUNT(*) FROM sn_key k LEFT JOIN sn_item i ON k.pi_no=i.pi_no AND k.sn=i.sn WHERE i.id IS NULL"),
            block("SN_KEY_SEQUENCE_MISMATCH","sn_item","SELECT COUNT(*) FROM sn_item i JOIN sn_key k ON k.pi_no=i.pi_no AND k.sn=i.sn WHERE NOT (i.seq_dec<=>k.seq_dec) OR (i.seq_dec IS NOT NULL AND NOT (i.seq_pi_no<=>k.seq_pi_no))"),
            block("SN_FULL_DUPLICATE","sn_item","SELECT COUNT(*) FROM (SELECT pi_no,sn FROM sn_item GROUP BY pi_no,sn HAVING COUNT(*)<>1) x"),
            block("SN_SEQUENCE_DUPLICATE","sn_item","SELECT COUNT(*) FROM (SELECT seq_pi_no,seq_dec FROM sn_item WHERE seq_dec IS NOT NULL GROUP BY seq_pi_no,seq_dec HAVING COUNT(*)<>1) x"),
            block("SN_WATERMARK_MISSING_OR_LOW","sn_item","SELECT COUNT(*) FROM sn_item i LEFT JOIN sn_pi_counter p ON p.pi_no=i.seq_pi_no WHERE i.seq_dec IS NOT NULL AND (p.pi_no IS NULL OR p.last_seq_dec<i.seq_dec)"),
            block("SN_INVALID_ENUM_OR_MONTH","sn_item","SELECT COUNT(*) FROM sn_item WHERE status NOT IN ('PENDING_ALLOC','TO_ACQUIRE','TO_PRINT','PRINTED','APPLYING') OR source NOT IN ('GEN','IMPORT') OR gen_month<197001 OR gen_month>999712 OR MOD(gen_month,100) NOT BETWEEN 1 AND 12 OR seq_dec<0"),
            block("SN_FACTORY_ORPHAN","sn_item","SELECT COUNT(*) FROM sn_item i LEFT JOIN sn_factory f ON f.factory_code=i.factory_code WHERE i.factory_code IS NOT NULL AND f.factory_code IS NULL"),
            block("SN_RULE_ORPHAN","sn_item","SELECT COUNT(*) FROM sn_item i LEFT JOIN sn_rule_version v ON v.id=i.rule_version_id WHERE i.rule_version_id IS NOT NULL AND v.id IS NULL"),
            block("RULE_VERSION_ORPHAN","sn_rule_version","SELECT COUNT(*) FROM sn_rule_version v LEFT JOIN sn_rule r ON r.id=v.rule_id WHERE r.id IS NULL"),
            block("RULE_CURRENT_MISSING","sn_rule","SELECT COUNT(*) FROM sn_rule r LEFT JOIN sn_rule_version v ON v.rule_id=r.id AND v.version=r.current_version WHERE v.id IS NULL"),
            block("RULE_INVALID_ENCODING","sn_rule_version","SELECT COUNT(*) FROM sn_rule_version WHERE base NOT IN (10,16,32,36) OR seq_len NOT BETWEEN 1 AND 12 OR CHAR_LENGTH(charset)<>base OR CHAR_LENGTH(prefix)+CHAR_LENGTH(suffix)+seq_len>64"),
            block("PI_COUNTS_MISMATCH","sn_pi_counter","SELECT COUNT(*) FROM sn_pi_counter p LEFT JOIN (SELECT seq_pi_no,SUM(source='GEN') AS generated_count,SUM(source='IMPORT') AS imported_count FROM sn_item GROUP BY seq_pi_no) i ON i.seq_pi_no=p.pi_no WHERE p.generated_qty<>COALESCE(i.generated_count,0) OR p.imported_qty<>COALESCE(i.imported_count,0) OR p.last_seq_dec<0 OR (p.start_seq_dec IS NOT NULL AND p.start_seq_dec<=0)"),
            block("GENERATED_JOB_ORPHAN","sn_item","SELECT COUNT(*) FROM sn_item i LEFT JOIN sn_gen_job j ON j.id=i.job_id WHERE i.source='GEN' AND (j.id IS NULL OR j.status<>'SUCCESS')"),
            block("JOB_RUNNING_REQUIRES_RECONCILIATION","sn_gen_job","SELECT COUNT(*) FROM sn_gen_job WHERE status='RUNNING' OR running_pi IS NOT NULL"),
            block("JOB_COUNT_MISMATCH","sn_gen_job","SELECT COUNT(*) FROM sn_gen_job j LEFT JOIN (SELECT job_id,COUNT(*) AS n FROM sn_item WHERE job_id IS NOT NULL GROUP BY job_id) i ON i.job_id=j.id WHERE (j.status='SUCCESS' AND (j.qty<>j.done_qty OR j.qty<>COALESCE(i.n,0))) OR (j.status='FAILED' AND COALESCE(i.n,0)>0) OR j.status NOT IN ('SUCCESS','FAILED','RUNNING')"),
            block("USER_UNSUPPORTED_IDENTITY","sn_user","SELECT COUNT(*) FROM sn_user WHERE role NOT IN ('admin','factory_operator','query') OR status NOT IN ('ACTIVE','DISABLED') OR (lang IS NOT NULL AND lang NOT IN ('zh-CN','en','vi')) OR NOT REGEXP_LIKE(password_hash,'^[0-9a-f]{96}$','c')"),
            block("USER_FACTORY_ORPHAN","sn_user","SELECT COUNT(*) FROM sn_user u LEFT JOIN sn_factory f ON f.factory_code=u.factory_code WHERE (u.role='factory_operator' AND u.factory_code IS NULL) OR (u.factory_code IS NOT NULL AND f.factory_code IS NULL)"),
            block("FACTORY_TARGET_COLLATION_CONFLICT","sn_factory","SELECT COUNT(*) FROM (SELECT factory_name COLLATE utf8mb4_0900_as_cs FROM sn_factory GROUP BY factory_name COLLATE utf8mb4_0900_as_cs HAVING COUNT(*)>1) x"),
            block("ORDER_FRACTIONAL_OR_NEGATIVE_QUANTITY","sn_prd_mo","SELECT COUNT(*) FROM sn_prd_mo WHERE qty<0 OR qty<>FLOOR(qty) OR status NOT IN ('1','2')"),
            block("ERP_STABLE_ID_EVIDENCE_REQUIRED","sn_prd_mo","SELECT COUNT(*) FROM sn_prd_mo"),
            block("SOURCE_QUOTA_EVIDENCE_REQUIRED","sn_bill_gen","SELECT COUNT(*) FROM sn_bill_gen"),
            block("ALLOCATION_MEMBERS_EVIDENCE_REQUIRED","sn_allocation","SELECT COUNT(*) FROM sn_allocation"),
            block("ACQUIRE_SNAPSHOT_EVIDENCE_REQUIRED","sn_acquire_batch","SELECT COUNT(*) FROM sn_acquire_batch"),
            block("PRINT_MEMBERS_AND_FILES_REQUIRED","sn_print","SELECT COUNT(*) FROM sn_print"),
            block("TRANSFER_SNAPSHOT_EVIDENCE_REQUIRED","sn_transfer","SELECT COUNT(*) FROM sn_transfer"),
            block("TRANSFER_ITEM_ORPHAN","sn_transfer_item","SELECT COUNT(*) FROM sn_transfer_item m LEFT JOIN sn_transfer t ON t.id=m.transfer_id LEFT JOIN sn_item i ON i.id=m.item_id AND i.gen_month=m.gen_month WHERE t.id IS NULL OR i.id IS NULL"),
            block("INFLIGHT_MEMBERS_REQUIRE_CUTOVER_BINDING","sn_item","SELECT COUNT(*) FROM sn_item WHERE status IN ('TO_PRINT','APPLYING')"),
            block("AUDIT_ACTOR_AND_ACTION_MAPPING_REQUIRED","sn_audit","SELECT (SELECT COUNT(*) FROM sn_audit)+(SELECT COUNT(*) FROM sn_audit_archive)"),
            new Check("PREVIEWS_INVALIDATED_AT_CUTOVER","sn_gen_preview","SELECT COUNT(*) FROM sn_gen_preview","EXPIRE_NOT_RESUME")
        );
        var issues=new ArrayList<Issue>();
        for(var check:checks)try(var s=c.createStatement()){
            s.setQueryTimeout(120);try(var r=s.executeQuery(check.sql())){
                r.next();long count=r.getLong(1);if(count>0)issues.add(new Issue(check.code(),check.table(),Long.toString(count),check.disposition()));
            }
        }catch(SQLException error){throw new IllegalStateException("LEGACY_CHECK_"+check.code()+"_SQL_"+error.getErrorCode());}
        return List.copyOf(issues);
    }
    private static Check block(String code,String table,String sql){return new Check(code,table,sql,"BLOCK_PROMOTION");}
}
