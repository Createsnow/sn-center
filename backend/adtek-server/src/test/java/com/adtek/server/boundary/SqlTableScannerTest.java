package com.adtek.server.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlTableScannerTest {
    @Test
    void readsTablesFromDdl() {
        String sql =
                """
                -- 注释里的 from other_table 不算
                CREATE TABLE IF NOT EXISTS `order_item` (
                  id BIGINT PRIMARY KEY,
                  order_id BIGINT NOT NULL,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    COMMENT 'from text_table',
                  CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES order_head (id)
                    ON DELETE CASCADE ON UPDATE CASCADE
                ) ENGINE=InnoDB;
                ALTER TABLE order_item ADD INDEX idx_order (order_id), ALGORITHM=INPLACE;
                CREATE UNIQUE INDEX uk_code ON order_code (code);
                DROP TABLE IF EXISTS order_old, order_older;
                RENAME TABLE order_tmp TO order_final;
                ALTER TABLE order_a RENAME TO order_b;
                CREATE TABLE order_copy LIKE order_source;
                CREATE VIEW order_view AS SELECT id FROM order_head;
                TRUNCATE TABLE order_log;
                """;

        assertThat(SqlTableScanner.tables(sql))
                .containsExactlyInAnyOrder(
                        "order_item",
                        "order_head",
                        "order_code",
                        "order_old",
                        "order_older",
                        "order_tmp",
                        "order_final",
                        "order_a",
                        "order_b",
                        "order_copy",
                        "order_source",
                        "order_view",
                        "order_log");
    }

    @Test
    void readsTablesFromDml() {
        String sql =
                """
                INSERT INTO order_head (id, code) SELECT id, code FROM order_draft d
                  ON DUPLICATE KEY UPDATE code = VALUES(code);
                UPDATE order_head h JOIN order_item i ON i.order_id = h.id SET h.total = 1;
                UPDATE order_a, order_b SET order_a.x = order_b.x;
                DELETE h FROM order_head h STRAIGHT_JOIN order_flag f ON f.id = h.id;
                SELECT a.id FROM order_a AS a, order_c c LEFT JOIN order_d d ON d.id = c.id
                  WHERE a.id IN (SELECT id FROM order_e) AND EXISTS (SELECT 1 FROM order_f)
                  AND EXTRACT(YEAR FROM a.created_at) = 2026 AND TRIM(LEADING 'x' FROM a.code) <> ''
                  FOR UPDATE;
                SELECT 1 FROM DUAL;
                WITH recent AS (SELECT id FROM order_g) SELECT id FROM recent;
                REPLACE INTO order_h (id) VALUES (1);
                """;

        assertThat(SqlTableScanner.tables(sql))
                .containsExactlyInAnyOrder(
                        "order_head",
                        "order_draft",
                        "order_item",
                        "order_a",
                        "order_b",
                        "order_flag",
                        "order_c",
                        "order_d",
                        "order_e",
                        "order_f",
                        "order_g",
                        "order_h");
    }

    @Test
    void reportsSchemaQualifiedNamesAsWritten() {
        assertThat(SqlTableScanner.tables("SELECT * FROM other_db.system_user"))
                .containsExactly("other_db.system_user");
    }

    @Test
    void readsTablesFromMapperXmlIncludingFragments() {
        String xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                  "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.adtek.order.mapper.OrderMapper">
                  <!-- SELECT * FROM commented_table -->
                  <sql id="columns">o.id, o.code</sql>
                  <select id="page" resultType="com.adtek.order.domain.OrderEntity">
                    SELECT <include refid="columns"/>
                    FROM order_head o
                    <where>
                      <if test="query.code != null and query.code != ''">AND o.code = #{query.code}</if>
                      <if test="query.minTotal != null">AND o.total &gt;= #{query.minTotal}</if>
                      AND o.created_at &lt; #{query.before}
                      AND o.dept_id IN
                      <foreach collection="deptIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                    </where>
                  </select>
                  <update id="updateWithVersion">
                    UPDATE order_head SET code = #{entity.code}, version = version + 1
                    WHERE id = #{entity.id} AND version = #{entity.version}
                  </update>
                  <select id="sneaky"><![CDATA[ SELECT id FROM system_user WHERE id < 10 ]]></select>
                </mapper>
                """;

        assertThat(SqlTableScanner.tablesInMapperXml(xml))
                .containsExactlyInAnyOrder("order_head", "system_user");
    }
}
