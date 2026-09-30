package com.adtek.sn.service.support;

import com.adtek.sn.vo.LegacySnapshotManifestVO;
import com.adtek.sn.vo.LegacySnapshotManifestVO.Table;
import com.adtek.sn.dto.LegacyStageRowDTO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.sql.SQLException;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 受控原始快照格式解析；不执行文件中的SQL/路径，逐行字段只允许字符串或null。 */
public final class LegacySnapshotPackage {
    private final Path directory;
    private final ObjectMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final LegacySnapshotManifestVO manifest;
    private final String manifestDigest;
    @FunctionalInterface public interface RowConsumer { void accept(LegacyStageRowDTO row)throws SQLException; }
    public LegacySnapshotPackage(Path root,String snapshotId)throws IOException{
        if(root==null||!UUID.fromString(snapshotId).toString().equals(snapshotId))throw refused();
        directory=root.toAbsolutePath().normalize().resolve(snapshotId);
        Path file=safeFile("manifest.json");if(Files.size(file)>16777216)throw refused();
        manifest=json.readValue(Files.readString(file),LegacySnapshotManifestVO.class);manifestDigest=hash(file);
        require("adtek-legacy-snapshot-v1".equals(manifest.format())&&snapshotId.equals(manifest.runId())&&"sn-python-repository-v1".equals(manifest.schemaVersion())&&manifest.sourceReadOnly());
        require(manifest.tables()!=null&&manifest.tables().size()==19&&manifest.schemaMetadata()!=null&&manifest.issues()!=null);
        var seen=new HashSet<String>();
        try(var stream=LegacySnapshotPackage.class.getResourceAsStream("/legacy/repository-v1.json")){
            var known=json.readTree(stream);require(known.path("sourceSqlSha256").asString().equals(manifest.sourceSqlSha256()));
            for(int i=0;i<19;i++){
                var t=manifest.tables().get(i);var expected=known.path("tables").get(i);
                require(t.name().equals(expected.path("name").asString())&&seen.add(t.name())&&t.file().equals(t.name()+".ndjson"));
                var columns=new ArrayList<String>();for(var e:expected.path("columns"))columns.add(e.path("name").asString());
                var keys=new ArrayList<String>();for(var e:expected.path("primaryKey"))keys.add(e.asString());
                require(t.columns().equals(columns)&&t.primaryKey().equals(keys));
                require(t.rows().matches("0|[1-9][0-9]*")&&Long.parseLong(t.rows())<=150000000&&t.bytes().matches("0|[1-9][0-9]*")&&Long.parseLong(t.bytes())<=1099511627776L);
                Path data=safeFile(t.file());require(Files.size(data)==Long.parseLong(t.bytes())&&hash(data).equals(t.sha256()));
            }
        }
        require(sha(json.writeValueAsBytes(manifest.schemaMetadata())).equals(manifest.schemaDigest()));
        require(sha(json.writeValueAsBytes(List.of(manifest.schemaVersion(),manifest.sourceSchema(),manifest.sourceServerUuid(),manifest.sourceZone(),manifest.schemaDigest(),manifest.tables()))).equals(manifest.sourceFingerprint()));
    }
    public LegacySnapshotManifestVO manifest(){return manifest;}
    public String manifestDigest(){return manifestDigest;}
    public String manifestJson(){return json.writeValueAsString(manifest);}
    /** 执行前/重放后逐行检查，NULL与空串保持区分，源复合主键生成稳定键摘要。 */
    public void rows(Table table,RowConsumer consumer)throws IOException,SQLException{
        long count=0;
        try(var reader=new BufferedReader(new LimitedLineReader(Files.newBufferedReader(safeFile(table.file()),StandardCharsets.UTF_8)))){
            String line;
            while((line=reader.readLine())!=null){
                if(++count>Long.parseLong(table.rows()))throw refused();
                var row=json.readTree(line);var ordered=ordered(table,row);
                var key=new ArrayList<String>();for(var field:table.primaryKey()){String value=ordered.get(field);require(value!=null);key.add(value);}
                String canonical=json.writeValueAsString(ordered);
                consumer.accept(new LegacyStageRowDTO(count,sha(json.writeValueAsBytes(key)),canonical,sha(canonical.getBytes(StandardCharsets.UTF_8))));
            }
        }
        require(count==Long.parseLong(table.rows()));
        require(hash(safeFile(table.file())).equals(table.sha256()));
    }
    /** MySQL JSON会调整键序/空格；按固定旧字段序重编码，再验证真实payload而不只信任旁存摘要。 */
    public String payloadDigest(Table table,String payload){return sha(json.writeValueAsBytes(ordered(table,json.readTree(payload))));}
    private LinkedHashMap<String,String> ordered(Table table,JsonNode row){
        require(row!=null&&row.isObject()&&row.size()==table.columns().size());
        var values=new LinkedHashMap<String,String>();
        for(String field:table.columns()){var value=row.get(field);require(value!=null&&(value.isNull()||value.isString()));values.put(field,value.isNull()?null:value.asString());}
        return values;
    }
    private Path safeFile(String name)throws IOException{
        require(name.matches("[a-z0-9_.-]+"));Path path=directory.resolve(name);
        for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p)||(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isOther()))throw refused();
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&path.toRealPath().startsWith(directory.toRealPath()));return path;
    }
    private static final class LimitedLineReader extends FilterReader {
        private int length;
        LimitedLineReader(Reader reader){super(reader);}
        @Override public int read(char[] buffer,int offset,int length)throws IOException{
            int n=super.read(buffer,offset,length);for(int i=offset;i<offset+Math.max(0,n);i++){if(buffer[i]=='\n')this.length=0;else if(++this.length>1048576)throw refused();}return n;
        }
    }
    public static String sha(byte[] value){return HexFormat.of().formatHex(digest().digest(value));}
    private static String hash(Path path)throws IOException{try(var input=Files.newInputStream(path)){var digest=digest();byte[] b=new byte[65536];int n;while((n=input.read(b))!=-1)digest.update(b,0,n);return HexFormat.of().formatHex(digest.digest());}}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    private static void require(boolean value){if(!value)throw refused();}
    private static IllegalStateException refused(){return new IllegalStateException("LEGACY_SNAPSHOT_INVALID");}
}
