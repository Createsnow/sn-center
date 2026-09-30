package com.adtek.sn.service.support;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.*;
import java.util.*;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 读取受控备份根目录中的恢复证据；检查实际备份/恢复文件，不能只凭restoreVerified布尔值删除在线痕迹。 */
public final class ArchiveRestoreProof {
    private final Path root;
    private final ObjectMapper json=new ObjectMapper();
    public record Verified(String proofDigest,String sourceUuid,String schema,long rows,String auditDigest) {}
    public ArchiveRestoreProof(Path root){this.root=root==null?null:root.toAbsolutePath().normalize();}
    /** 相对目录只能为backup UUID/restore-UUID；无链接、无任意SQL执行，损坏/缺失/范围不匹配整体拒绝。 */
    public Verified verify(String directory,YearMonth month){
        try{
            if(root==null||directory==null||!directory.matches("[0-9a-f-]{36}/restore-[0-9a-f-]{36}"))throw refused();
            String[] parts=directory.split("/");uuid(parts[0]);uuid(parts[1].substring(8));
            Path backup=root.resolve(parts[0]),restored=root.resolve(directory);
            Path manifestFile=file(backup.resolve("manifest.json")),proofFile=file(restored.resolve("restore-proof.json"));
            JsonNode manifest=read(manifestFile),proof=read(proofFile);
            require(text(manifest,"format").equals("adtek-container-backup-v1")&&text(proof,"format").equals("adtek-container-restore-proof-v1"));
            require(text(manifest,"runId").equals(parts[0])&&text(proof,"backupRunId").equals(parts[0])&&text(proof,"restoreRunId").equals(parts[1].substring(8)));
            require(hash(manifestFile).equals(text(proof,"backupManifestSha256")));
            require(proof.path("restoreVerified").asBoolean(false)&&proof.path("canonicalSqlIdentical").asBoolean(false)&&proof.path("filesIdentical").asBoolean(false));
            require(text(proof,"canonicalAlgorithm").equals("mysql-dump-utf8mb4-redundancy-v1"));
            Path sourceSql=file(backup.resolve("database.sql")),targetSql=file(restored.resolve("restored.sql"));
            String sourceHash=hash(sourceSql),targetHash=hash(targetSql);
            require(sourceHash.equals(text(manifest.path("database"),"sha256"))&&sourceHash.equals(text(proof,"databaseSha256"))&&targetHash.equals(text(proof,"restoredDumpSha256")));
            String canonical=canonical(sourceSql);
            require(canonical.equals(canonical(targetSql))&&canonical.equals(text(proof,"canonicalSqlSha256")));
            Path sourceTar=file(backup.resolve("files.tar")),targetTar=file(restored.resolve("restored-files.tar"));
            require(hash(sourceTar).equals(text(manifest.path("fileArchive"),"sha256")));
            var expected=new HashMap<String,FileDigest>();
            for(var item:manifest.path("files")){
                String name=text(item,"path");var entry=new FileDigest(Long.parseLong(text(item,"bytes")),text(item,"sha256"));
                require(expected.put(name,entry)==null);
            }
            require(expected.size()==proof.path("fileCount").asInt(-1));
            require(expected.equals(tar(sourceTar))&&expected.equals(tar(targetTar)));
            Path browserFile=file(restored.resolve("browser.json"));
            require(hash(browserFile).equals(text(proof.path("browser"),"reportSha256")));
            var stats=read(browserFile).path("stats");
            require(stats.path("expected").asInt(-1)==1&&stats.path("unexpected").asInt(-1)==0&&stats.path("skipped").asInt(-1)==0&&stats.path("flaky").asInt(-1)==0);
            require(Instant.parse(text(proof,"completedAtUtc")).compareTo(Instant.parse(text(manifest,"completedAtUtc")))>=0);
            String sourceUuid=identity(text(manifest,"sourceIdentity")),targetUuid=identity(text(proof,"targetIdentity"));
            require(!sourceUuid.equals(targetUuid));
            JsonNode archive=null;
            for(var entry:proof.path("auditArchives"))if(month.toString().equals(text(entry,"month"))){require(archive==null);archive=entry;}
            require(archive!=null);
            long rows=Long.parseLong(text(archive,"onlineRows"));String digest=text(archive,"onlineDigest");
            require(rows>0&&rows==Long.parseLong(text(archive,"archiveRows"))&&digest.matches("[0-9a-f]{64}")&&digest.equals(text(archive,"archiveDigest")));
            return new Verified(hash(proofFile),sourceUuid,text(manifest,"sourceDatabase"),rows,digest);
        }catch(Exception failure){throw refused();}
    }
    private record FileDigest(long bytes,String sha256) {}
    private Map<String,FileDigest> tar(Path file)throws IOException{
        var entries=new HashMap<String,FileDigest>();
        try(var input=new TarArchiveInputStream(Files.newInputStream(file))){
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while((entry=input.getNextEntry())!=null){
                String name=entry.getName();while(name.startsWith("./"))name=name.substring(2);
                if(entry.isDirectory())continue;
                require(entry.isFile()&&!entry.isSparse()&&!entry.isLink()&&!entry.isSymbolicLink());
                require(name.matches("[A-Za-z0-9_./-]+")&&!name.startsWith("/")&&!Arrays.asList(name.split("/",-1)).contains(".."));
                require(entries.put(name,new FileDigest(entry.getSize(),digest(input)))==null);
            }
        }
        return entries;
    }
    private JsonNode read(Path file)throws IOException{require(Files.size(file)<=16777216);return json.readTree(Files.readString(file,StandardCharsets.UTF_8));}
    private Path file(Path path)throws IOException{
        require(path.normalize().startsWith(root));
        for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p)||(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isOther()))throw refused();
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&path.toRealPath().startsWith(root.toRealPath()));return path;
    }
    private String hash(Path file)throws IOException{try(var stream=Files.newInputStream(file)){return digest(stream);}}
    private String digest(InputStream stream)throws IOException{var hash=sha();byte[] bytes=new byte[65536];int n;while((n=stream.read(bytes))!=-1)hash.update(bytes,0,n);return HexFormat.of().formatHex(hash.digest());}
    private String canonical(Path path)throws IOException{
        var hash=sha();boolean inTable=false;
        try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){
            String line;
            while((line=reader.readLine())!=null){
                if(line.startsWith("CREATE TABLE `"))inTable=true;
                if(inTable&&line.matches("^  `[^`]+` .*"))line=line.replaceAll(" CHARACTER SET utf8mb4(?= COLLATE utf8mb4_[A-Za-z0-9_]+(?: |,|$))","");
                if(line.startsWith(") ENGINE="))inTable=false;
                hash.update((line+"\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private String identity(String value){String first=value.length()>=36?value.substring(0,36):"";uuid(first);require(value.substring(36).startsWith("\t")||value.substring(36).startsWith("\\t"));return first;}
    private void uuid(String value){require(UUID.fromString(value).toString().equals(value));}
    private String text(JsonNode node,String key){String value=node.path(key).asString();require(value!=null&&!value.isBlank());return value;}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void require(boolean condition){if(!condition)throw refused();}
    private static IllegalStateException refused(){return new IllegalStateException("ARCHIVE_RESTORE_PROOF_INVALID");}
}
