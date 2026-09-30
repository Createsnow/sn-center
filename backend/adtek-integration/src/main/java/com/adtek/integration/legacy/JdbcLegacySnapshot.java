package com.adtek.integration.legacy;

import com.adtek.sn.dto.LegacySnapshotCommandDTO;
import com.adtek.sn.service.port.LegacySnapshotPort;
import com.adtek.sn.vo.LegacySnapshotManifestVO;
import com.adtek.sn.vo.LegacySnapshotManifestVO.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import tools.jackson.databind.*;

/** 封闭19表读取适配器；JDBC显式只读一致快照，字段原文保留，元数据前后核验防止换版本续传。 */
final class JdbcLegacySnapshot implements LegacySnapshotPort {
    private final DataSource source;
    private final ObjectMapper json=new ObjectMapper();
    JdbcLegacySnapshot(DataSource source){this.source=Objects.requireNonNull(source);}
    @Override public LegacySnapshotManifestVO capture(LegacySnapshotCommandDTO command){
        Path output=command.outputDirectory().toAbsolutePath().normalize();
        try(var c=source.getConnection();var descriptor=JdbcLegacySnapshot.class.getResourceAsStream("/legacy/repository-v1.json")){
            if(!command.sourceSchema().equals(c.getCatalog())||!command.sourceSchema().equals(scalar(c,"SELECT DATABASE()")))throw failure("SCHEMA_MISMATCH");
            if(c.getMetaData().getDatabaseMajorVersion()!=8)throw failure("MYSQL_VERSION");
            var contract=json.readTree(descriptor);var definitions=contract.path("tables");
            var before=metadata(c);validate(contract,before);
            String schemaDigest=sha(json.writeValueAsBytes(before));
            if(Files.exists(output,LinkOption.NOFOLLOW_LINKS))throw failure("OUTPUT_EXISTS");
            Files.createDirectories(output);
            if(Files.getFileStore(output).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(output,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            // READ ONLY由MySQL执行；使用一致事务而不是逐表独立读取，断开时自动回滚且不修改源数据。
            execute(c,"SET SESSION time_zone='+00:00'");execute(c,"SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            execute(c,"START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
            var tables=new ArrayList<Table>();var issues=new ArrayList<Issue>();long totalBytes=0;
            String server=scalar(c,"SELECT @@server_uuid"),mysql=scalar(c,"SELECT @@version");
            for(JsonNode definition:definitions){
                String table=definition.path("name").asString();var columns=strings(definition.path("columns"),"name");var keys=strings(definition.path("primaryKey"),null);
                long count=Long.parseLong(scalar(c,"SELECT COUNT(*) FROM "+table));
                if(count>command.rowLimit())throw failure("ROW_LIMIT");
                Path file=output.resolve(table+".ndjson");MessageDigest hash=digest();long written=0,bytes=0;
                var dateColumns=new HashSet<String>();for(var column:definition.path("columns"))if(column.path("type").asString().startsWith("datetime"))dateColumns.add(column.path("name").asString());
                try(var p=c.prepareStatement("SELECT "+identifiers(columns)+" FROM "+table+" ORDER BY "+identifiers(keys));var stream=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){
                    p.setQueryTimeout(120);p.setFetchSize(Integer.MIN_VALUE);
                    try(var r=p.executeQuery()){
                        while(r.next()){
                            var row=new LinkedHashMap<String,String>();
                            for(int i=0;i<columns.size();i++){
                                String column=columns.get(i),value=r.getString(i+1);
                                if(value!=null&&dateColumns.contains(column)){
                                    var local=LocalDateTime.parse(value.replace(' ','T'));
                                    if(ZoneId.of(command.sourceZone()).getRules().getValidOffsets(local).size()!=1)throw failure("TIME_AMBIGUOUS");
                                }
                                row.put(column,value);
                            }
                            byte[] line=(json.writeValueAsString(row)+"\n").getBytes(StandardCharsets.UTF_8);
                            bytes+=line.length;if(++written>command.rowLimit()||totalBytes+bytes>command.byteLimit())throw failure("RESOURCE_LIMIT");
                            stream.write(line);hash.update(line);
                        }
                    }
                }
                if(written!=count)throw failure("SNAPSHOT_COUNT_CHANGED");
                totalBytes+=bytes;tables.add(new Table(table,file.getFileName().toString(),columns,keys,Long.toString(written),Long.toString(bytes),HexFormat.of().formatHex(hash.digest())));
            }
            issues.addAll(LegacyPreflightChecks.check(c));
            if(!before.equals(metadata(c)))throw failure("SCHEMA_CHANGED");
            execute(c,"ROLLBACK");
            String fingerprint=sha(json.writeValueAsBytes(List.of(command.schemaVersion(),command.sourceSchema(),server,command.sourceZone(),schemaDigest,tables)));
            // readyForPromotion保持false：原始快照不是稳定来源映射/固定历史/文件核对后的受验收导入包。
            var result=new LegacySnapshotManifestVO("adtek-legacy-snapshot-v1",command.runId(),command.schemaVersion(),contract.path("sourceSqlSha256").asString(),command.sourceSchema(),server,mysql,command.sourceZone(),Instant.now().toString(),schemaDigest,fingerprint,List.copyOf(tables),List.copyOf(issues),before,true,false);
            Path temp=output.resolve("manifest.pending");Files.writeString(temp,json.writeValueAsString(result),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            Files.move(temp,output.resolve("manifest.json"),StandardCopyOption.ATOMIC_MOVE);
            return result;
        }catch(SQLException error){throw failure("SQL_"+error.getErrorCode());}
        catch(IOException error){throw failure("FILE_IO");}
    }
    private List<Map<String,String>> metadata(Connection c)throws SQLException{
        var result=new ArrayList<Map<String,String>>();
        result.addAll(query(c,"SELECT 'table' AS kind,table_name,engine,table_collation FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name"));
        result.addAll(query(c,"SELECT 'column' AS kind,table_name,column_name,column_type,is_nullable,collation_name,column_default,extra FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position"));
        result.addAll(query(c,"SELECT 'index' AS kind,table_name,index_name,CAST(non_unique AS CHAR) AS non_unique,column_name,CAST(seq_in_index AS CHAR) AS seq FROM information_schema.statistics WHERE table_schema=DATABASE() ORDER BY table_name,index_name,seq_in_index"));
        result.addAll(query(c,"SELECT 'partition' AS kind,table_name,partition_name,partition_method,partition_expression,partition_description FROM information_schema.partitions WHERE table_schema=DATABASE() ORDER BY table_name,partition_ordinal_position"));
        if(result.stream().anyMatch(x->"table".equals(x.get("kind"))&&"flyway_schema_history".equals(x.get("table_name"))))
            result.addAll(query(c,"SELECT 'history' AS kind,version,description,type,script,CAST(checksum AS CHAR) AS checksum,CAST(success AS CHAR) AS success FROM flyway_schema_history ORDER BY installed_rank LIMIT 10001"));
        return List.copyOf(result);
    }
    private void validate(JsonNode contract,List<Map<String,String>> actual){
        var expectedNames=new TreeSet<String>();
        for(var t:contract.path("tables")){
            String name=t.path("name").asString();expectedNames.add(name);
            var tables=actual.stream().filter(x->"table".equals(x.get("kind"))&&name.equals(x.get("table_name"))).toList();
            require(tables.size()==1&&"InnoDB".equals(tables.getFirst().get("engine"))&&"utf8mb4_bin".equals(tables.getFirst().get("table_collation")));
            var cols=actual.stream().filter(x->"column".equals(x.get("kind"))&&name.equals(x.get("table_name"))).toList();
            require(cols.size()==t.path("columns").size());
            for(int i=0;i<cols.size();i++){
                var e=t.path("columns").get(i);var a=cols.get(i);
                require(e.path("name").asString().equals(a.get("column_name"))&&e.path("type").asString().equals(a.get("column_type"))
                        &&e.path("nullable").asBoolean()=="YES".equals(a.get("is_nullable"))&&Objects.equals(e.path("collation").asString(null),a.get("collation_name")));
            }
            var pk=actual.stream().filter(x->"index".equals(x.get("kind"))&&name.equals(x.get("table_name"))&&"PRIMARY".equals(x.get("index_name"))).map(x->x.get("column_name")).toList();
            require(pk.equals(strings(t.path("primaryKey"),null)));
            var expectedUnique=new TreeMap<String,List<String>>();
            for(var property:t.path("uniqueKeys").properties())expectedUnique.put(property.getKey(),strings(property.getValue(),null));
            var actualUnique=new TreeMap<String,List<String>>();
            for(var x:actual)if("index".equals(x.get("kind"))&&name.equals(x.get("table_name"))&&"0".equals(x.get("non_unique"))&&!"PRIMARY".equals(x.get("index_name")))
                actualUnique.computeIfAbsent(x.get("index_name"),ignored->new ArrayList<>()).add(x.get("column_name"));
            require(expectedUnique.equals(actualUnique));
            var parts=actual.stream().filter(x->"partition".equals(x.get("kind"))&&name.equals(x.get("table_name"))).toList();
            require(!parts.isEmpty());
            if(t.path("partitioned").asBoolean()){
                String method=name.equals("sn_item")?"RANGE":"RANGE COLUMNS",field=name.equals("sn_item")?"gen_month":"created_at";
                require(parts.stream().allMatch(x->method.equals(x.get("partition_method"))&&field.equals(Objects.toString(x.get("partition_expression"),"").replace("`",""))));
                require("MAXVALUE".equals(parts.getLast().get("partition_description")));
            }else require(parts.size()==1&&parts.getFirst().get("partition_name")==null);
        }
        var names=new TreeSet<String>();for(var x:actual)if("table".equals(x.get("kind"))&&!"flyway_schema_history".equals(x.get("table_name")))names.add(x.get("table_name"));
        require(expectedNames.equals(names));
        require(actual.stream().filter(x->"history".equals(x.get("kind"))).count()<=10000);
        require(actual.stream().noneMatch(x->"history".equals(x.get("kind"))&&!"1".equals(x.get("success"))));
    }
    private static List<String> strings(JsonNode node,String field){var values=new ArrayList<String>();for(var e:node)values.add(field==null?e.asString():e.path(field).asString());return List.copyOf(values);}
    private static String identifiers(List<String> columns){return String.join(",",columns.stream().map(x->"`"+x+"`").toList());}
    private static List<Map<String,String>> query(Connection c,String sql)throws SQLException{
        var result=new ArrayList<Map<String,String>>();try(var s=c.createStatement()){s.setQueryTimeout(120);try(var r=s.executeQuery(sql)){
            var m=r.getMetaData();while(r.next()){var row=new LinkedHashMap<String,String>();for(int i=1;i<=m.getColumnCount();i++)row.put(m.getColumnLabel(i).toLowerCase(Locale.ROOT),r.getString(i));result.add(row);}
        }}return result;
    }
    private static String scalar(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(120);try(var r=s.executeQuery(sql)){if(!r.next())throw failure("MISSING_METADATA");return r.getString(1);}}}
    private static void execute(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.setQueryTimeout(120);s.execute(sql);}}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String sha(byte[] value){return HexFormat.of().formatHex(digest().digest(value));}
    private static void require(boolean value){if(!value)throw failure("UNSUPPORTED_SCHEMA");}
    private static IllegalStateException failure(String code){return new IllegalStateException("LEGACY_"+code);}
}
