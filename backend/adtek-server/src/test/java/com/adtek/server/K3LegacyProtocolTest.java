package com.adtek.server;

import com.adtek.integration.k3.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** 对照旧Python请求信封/多语言/二次JSON编码，仍走真实回环HTTP和Cookie，不接真实账套。 */
class K3LegacyProtocolTest {
    @Test void legacyEnvelopeAndWrappedNamesWorkForFullSingleAndOrganizationQueries(){
        try(var f=new K3ProtocolFixture()){
            f.legacyWire=true;
            var material=Map.of("Name",List.of(Map.of("Key",1033,"Value","Material"),Map.of("Key",2052,"Value","中文物料")));
            var organization=Map.of("Value","中文工厂");
            var row=new ArrayList<>(K3ProtocolFixture.row("91","92","BILL-1","C","PI","101","ORG","厂","M","7"));
            row.set(5,material);row.set(9,organization);f.orders=List.of(row);
            f.organizations=List.of(List.of("101","ORG",organization,"A"));
            var json=new ObjectMapper();
            var adapter=new K3ProductionOrderAdapter(new K3Settings(true,f.url(),"fixture-account","fixture-app","fixture-secret","fixture-user",2052,1,1,1,5,2,3,6,8192),json);
            Instant before=Instant.now();
            var all=adapter.orders(null);var single=adapter.orders("BILL-1");var orgs=adapter.organizations();
            Instant after=Instant.now();
            assertThat(all).hasSize(1);assertThat(single).isEqualTo(all);
            assertThat(all.getFirst().materialName()).isEqualTo("中文物料");assertThat(all.getFirst().orgName()).isEqualTo("中文工厂");
            assertThat(all.getFirst().qty()).isEqualByComparingTo("7");assertThat(orgs.getFirst().name()).isEqualTo("中文工厂");
            assertThat(f.envelopes).hasSize(6);
            for(var envelope:f.envelopes){
                assertThat(envelope.path("format").asInt()).isEqualTo(1);assertThat(envelope.path("useragent").asString()).isEqualTo("ApiClient");
                assertThat(envelope.path("v").asString()).isEqualTo("1.0");UUID.fromString(envelope.path("rid").asString());
                String value=envelope.path("timestamp").asString();
                assertThat(value).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
                Instant instant=LocalDateTime.parse(value,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
                assertThat(instant).isBetween(before.minusSeconds(1),after);
            }
            var login=f.envelopes.getFirst().path("parameters");
            assertThat(login.size()).isEqualTo(5);assertThat(login.get(0).asString()).isEqualTo("fixture-account");
            assertThat(login.get(1).asString()).isEqualTo("fixture-user");assertThat(login.get(2).asString()).isEqualTo("fixture-app");
            assertThat(login.get(3).asString()).isEqualTo("fixture-secret");assertThat(login.get(4).asInt()).isEqualTo(2052);
            var query=json.readTree(f.envelopes.get(1).path("parameters").get(0).asString());
            assertThat(query.path("FormId").asString()).isEqualTo("PRD_MO");
            assertThat(query.path("FilterString").asString()).isEqualTo("(FStatus='1' OR FStatus='2')");
            assertThat(query.path("OrderString").asString()).isEqualTo("FID ASC, FTreeEntity_FEntryId ASC");
            assertThat(query.path("FieldKeys").asString()).isEqualTo("F_ora_kh.FNumber,FBillNo,F_HX_PO,F_HX_PI,FMaterialId.FNumber,FMaterialId.FName,FQty,FStatus,FSaleOrderNo,FPrdOrgId.FName,FID,FTreeEntity_FEntryId,FPrdOrgId,FPrdOrgId.FNumber");
        }
    }
}
