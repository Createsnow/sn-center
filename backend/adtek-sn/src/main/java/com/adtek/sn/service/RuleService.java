package com.adtek.sn.service;
import com.adtek.common.core.PageResult;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.SnPageQuery;
import com.adtek.sn.vo.*;
/** 规则页完整用例；共享规则管理要求对应权限的全部工厂资格。 */
public interface RuleService {
 /** list权限，按绑定范围/值/ID稳定分页；搜索包含匹配，不解释SQL通配符。 */
 PageResult<RuleVO> list(SnPageQuery query);
 /** list权限，已存在规则的历史版本倒序完整分页；没有规则404。 */
 PageResult<RuleVersionVO> versions(String ruleId,SnPageQuery query);
 /** create权限，唯一代码/绑定；规则头、首版、成功审计同事务，冲突409。 */
 RuleVO create(RuleSaveDTO command);
 /** update权限，安全锁→规则头→当前版本，revision旧值409。
  * 仅改名不出版本，未用参数原位更新，已用参数变化新增版本；历史编码不改变，审计失败全部回滚。
  */
 RuleChangeVO update(String ruleId,RuleSaveDTO command);
 /** preview权限；只计算样例和精确容量，不写规则/流水/额度，不接受超范围起号。 */
 RulePreviewVO preview(RulePreviewDTO command);
 /** effective权限及全部工厂资格；PI优先于客户再通用。未传客户时沿用旧行为，
  * 从当前快照按billNo/ID取PI的首个非空客户；没有规则返回null。 */
 RuleVersionVO effective(String pi,String customer);
}
