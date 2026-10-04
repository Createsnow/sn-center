package com.adtek.sn.mapper;

import com.adtek.sn.domain.OrderBillEntity;
import com.adtek.sn.domain.OrderPiEntity;
import com.adtek.sn.query.OrderCandidateQuery;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 服务逐次复核order:list并装配工厂交集；分录搜索仅选单，额度按稳定分录累计。 */
@Mapper
public interface OrderCandidateMapper {
    String GENERATED = "COALESCE(h.generated_qty,0)";
    String ALLOCATED = "COALESCE(h.allocated_qty,0)";
    String QUOTA = "CASE WHEN b.quantity_ready THEN COALESCE(r.remaining_qty,0) ELSE 0 END";
    String FROM =
            " FROM sn_order_bill b LEFT JOIN (SELECT bill_id,"
                    + " SUM(generated_qty) generated_qty,SUM(allocated_qty) allocated_qty"
                    + " FROM sn_source_entry_quota GROUP BY bill_id) h ON h.bill_id=b.id"
                    + " LEFT JOIN (SELECT e.bill_id,"
                    + " SUM(GREATEST(e.qty-q.generated_qty,0)) remaining_qty"
                    + " FROM sn_order_entry e JOIN sn_source_entry_quota q ON q.bill_id=e.bill_id"
                    + " AND q.source_entry_id=e.source_entry_id WHERE e.active=TRUE"
                    + " GROUP BY e.bill_id) r"
                    + " ON r.bill_id=b.id WHERE b.active=TRUE AND b.customer&lt;&gt;''"
                    + "<if test='!allFactories'><choose><when test='!factoryIds.isEmpty()'>"
                    + " AND b.factory_id IN <foreach collection='factoryIds' item='f' open='('"
                    + " separator=',' close=')'>#{f}</foreach></when><otherwise> AND 1=0"
                    + "</otherwise></choose></if>"
                    + "<if test='factoryId!=null'> AND b.factory_id=#{factoryId}</if>"
                    + "<if test='customer!=null'> AND b.customer=#{customer}</if>"
                    + "<if test='pi!=null'> AND b.pi=#{pi}</if>";
    String ORDER_FILTER =
            FROM
                    + "<if test='like!=null'> AND (b.bill_no LIKE #{like} ESCAPE '!'"
                    + " OR b.customer LIKE #{like} ESCAPE '!' OR b.pi LIKE #{like} ESCAPE '!'"
                    + " OR b.po LIKE #{like} ESCAPE '!' OR EXISTS (SELECT 1 FROM sn_order_entry e"
                    + " WHERE e.bill_id=b.id AND e.active=TRUE"
                    + " AND e.material LIKE #{like} ESCAPE '!'))</if>"
                    + "<if test='pending'> AND (("
                    + QUOTA
                    + ")&gt;0 OR "
                    + GENERATED
                    + "&gt;"
                    + ALLOCATED
                    + ")</if>";
    String PI_FILTER =
            FROM
                    + " AND b.pi&lt;&gt;''"
                    + "<if test='like!=null'> AND b.pi LIKE #{like} ESCAPE '!'</if> GROUP BY b.pi"
                    + "<if test='pending'> HAVING SUM("
                    + QUOTA
                    + ")&gt;0 OR SUM("
                    + GENERATED
                    + "-"
                    + ALLOCATED
                    + ")&gt;0</if>";

    @Select(
            "<script>SELECT b.*"
                    + ORDER_FILTER
                    + " ORDER BY b.bill_no DESC,b.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<OrderBillEntity> bills(OrderCandidateQuery query);

    @Select("<script>SELECT COUNT(*)" + ORDER_FILTER + "</script>")
    long billCount(OrderCandidateQuery query);

    @Select(
            "<script>SELECT b.pi,COUNT(*) bills,SUM(b.total_qty) total_qty,SUM("
                    + GENERATED
                    + ") generated_qty,SUM("
                    + ALLOCATED
                    + ") allocated_qty,SUM("
                    + GENERATED
                    + "-"
                    + ALLOCATED
                    + ") pending_quantity,SUM("
                    + QUOTA
                    + ") quota"
                    + PI_FILTER
                    + " ORDER BY MAX(b.bill_no) DESC,b.pi DESC"
                    + " LIMIT #{limit} OFFSET #{offset}</script>")
    List<OrderPiEntity> pis(OrderCandidateQuery query);

    @Select("<script>SELECT COUNT(*) FROM (SELECT b.pi" + PI_FILTER + ") p</script>")
    long piCount(OrderCandidateQuery query);
}
