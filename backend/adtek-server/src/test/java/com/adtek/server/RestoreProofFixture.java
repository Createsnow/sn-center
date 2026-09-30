package com.adtek.server;

import com.adtek.sn.mapper.MaintenanceMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.*;
import org.apache.commons.compress.archivers.tar.*;
import tools.jackson.databind.ObjectMapper;

/** 仅用于拒绝/事务边界测试的合成证据；真实恢复演练由PowerShell和HTTPS浏览器另行验证。 */
final class RestoreProofFixture {
    private static final ObjectMapper JSON=new ObjectMapper();
    final Path root,backup,restored;
    final String directory;
    final Map<String,Object> manifest=new LinkedHashMap<>(),proof=new LinkedHashMap<>();
    RestoreProofFixture(Path root,String schema,String server,YearMonth month,MaintenanceMapper.Digest audit)throws Exception{
        this.root=root;String backupId=UUID.randomUUID().toString(),restoreId=UUID.randomUUID().toString();
        directory=backupId+"/restore-"+restoreId;backup=root.resolve(backupId);restored=Files.createDirectories(root.resolve(directory));
        String sql="CREATE TABLE `fixture` (\n  `note` varchar(40) COLLATE utf8mb4_0900_as_cs DEFAULT NULL\n) ENGINE=InnoDB;\nINSERT INTO `fixture` VALUES ('中文');\n";
        Files.writeString(backup.resolve("database.sql"),sql);
        Files.writeString(restored.resolve("restored.sql"),sql.replace("COLLATE utf8mb4","CHARACTER SET utf8mb4 COLLATE utf8mb4"));
        byte[] bytes="固定文件\n9007199254740993\n".getBytes(StandardCharsets.UTF_8);
        try(var tar=new TarArchiveOutputStream(Files.newOutputStream(backup.resolve("files.tar")))){
            var entry=new TarArchiveEntry("objects/fixture.csv");entry.setSize(bytes.length);tar.putArchiveEntry(entry);tar.write(bytes);tar.closeArchiveEntry();
        }
        Files.copy(backup.resolve("files.tar"),restored.resolve("restored-files.tar"));
        Files.writeString(restored.resolve("browser.json"),"{\"stats\":{\"expected\":1,\"unexpected\":0,\"skipped\":0,\"flaky\":0}}");
        manifest.putAll(Map.of("format","adtek-container-backup-v1","runId",backupId,"sourceDatabase",schema,"sourceIdentity",server+"\\t8.4.11\\t"+schema,"completedAtUtc","2026-01-01T00:00:00Z"));
        manifest.put("database",Map.of("sha256",hash(backup.resolve("database.sql"))));
        manifest.put("fileArchive",Map.of("sha256",hash(backup.resolve("files.tar"))));
        manifest.put("files",List.of(Map.of("path","objects/fixture.csv","bytes",Integer.toString(bytes.length),"sha256",digest(bytes))));
        proof.putAll(Map.of("format","adtek-container-restore-proof-v1","backupRunId",backupId,"restoreRunId",restoreId,"restoreVerified",true,"canonicalSqlIdentical",true,"filesIdentical",true,"canonicalAlgorithm","mysql-dump-utf8mb4-redundancy-v1","fileCount",1));
        proof.putAll(Map.of("databaseSha256",hash(backup.resolve("database.sql")),"restoredDumpSha256",hash(restored.resolve("restored.sql")),"canonicalSqlSha256",digest(sql.getBytes(StandardCharsets.UTF_8)),"targetIdentity",UUID.randomUUID()+"\t8.4.11\t"+schema,"completedAtUtc","2026-01-01T01:00:00Z"));
        proof.put("browser",Map.of("reportSha256",hash(restored.resolve("browser.json"))));
        proof.put("auditArchives",List.of(Map.of("month",month.toString(),"onlineRows",Long.toString(audit.rows()),"archiveRows",Long.toString(audit.rows()),"onlineDigest",audit.sha256(),"archiveDigest",audit.sha256())));
        save();
    }
    void save()throws Exception{
        Files.writeString(backup.resolve("manifest.json"),JSON.writeValueAsString(manifest));
        proof.put("backupManifestSha256",hash(backup.resolve("manifest.json")));
        Files.writeString(restored.resolve("restore-proof.json"),JSON.writeValueAsString(proof));
    }
    static String hash(Path path)throws Exception{return digest(Files.readAllBytes(path));}
    private static String digest(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
}
