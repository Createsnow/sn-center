package com.adtek.framework.task;
import com.adtek.common.core.BusinessException;
import com.adtek.framework.task.domain.FileEntity;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Component;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;
import static com.adtek.framework.constant.ErrorCodeConstants.*;
/** 受控持久根目录；没有数据库/文件共同事务。发布后DB回滚留下的同键产物可校验复用。 */
@Component
public class FileStorage {
    @FunctionalInterface public interface Writer {void write(OutputStream output) throws Exception;}
    public record Published(String digest,long bytes) {}
    private final Path root;private final long limit;
    public FileStorage(TaskSettings settings) {
        root=Path.of(settings.fileRoot()).toAbsolutePath().normalize();limit=settings.fileBytes();
    }
    private Path directory(String name) throws IOException {
        rejectLinks(root);Files.createDirectories(root);rejectLinks(root);
        Path value=root.resolve(name).normalize();rejectLinks(value);Files.createDirectories(value);rejectLinks(value);
        if(!value.toRealPath().startsWith(root.toRealPath())) {throw new IOException("Storage containment rejected");}return value;
    }
    private void rejectLinks(Path path) throws IOException {
        for(Path current=path;current!=null;current=current.getParent()) {
            if(Files.isSymbolicLink(current) || (Files.exists(current,NOFOLLOW_LINKS) && Files.readAttributes(current,java.nio.file.attribute.BasicFileAttributes.class,NOFOLLOW_LINKS).isOther())) {throw new IOException("Storage link rejected");}
        }
    }
    private Path target(String key) throws IOException {
        if(key==null || !key.matches("[0-9a-f-]{36}\\.(csv|xlsx)") || !key.substring(0,36).equals(TaskAccess.uuid(key.substring(0,36)))) {throw new IOException("Invalid storage key");}
        Path value=directory("objects").resolve(key);rejectLinks(value);return value;
    }
    /** 调用方必须已持该任务控制锁；只清理同任务残留part，不递归扫描或删除已发布文件。 */
    public void cleanInterrupted(String taskId) throws IOException {
        String id=TaskAccess.uuid(taskId);Path parts=directory("parts");
        try(var stream=Files.newDirectoryStream(parts,id+"-*.part")) {
            int count=0;for(Path path:stream) {if(++count>100) {throw new IOException("Too many interrupted parts");}rejectLinks(path);if(Files.isRegularFile(path,NOFOLLOW_LINKS)) {Files.delete(path);}}
        }
    }
    /** 临时写/强制刷盘/摘要之后才发布；已有正确产物复用，损坏产物移入受控隔离目录留证。 */
    public Published publish(String taskId,String key,Writer writer) throws Exception {
        return publish(taskId,key,null,writer);
    }
    /** 恢复已提交产物时先校验预期摘要，禁止变化的快照覆盖原对象。 */
    public Published publish(String taskId,String key,String expectedDigest,Writer writer) throws Exception {
        Path published=target(key);Path temp=directory("parts").resolve(TaskAccess.uuid(taskId)+"-"+UUID.randomUUID()+".part");
        MessageDigest digest=sha();long[] count={0};
        try {
            try(FileChannel channel=FileChannel.open(temp,CREATE_NEW,WRITE,NOFOLLOW_LINKS)) {
                OutputStream base=new java.security.DigestOutputStream(Channels.newOutputStream(channel),digest);
                OutputStream bounded=new FilterOutputStream(base) {
                    @Override public void write(int value) throws IOException {check(1);out.write(value);}
                    @Override public void write(byte[] value,int off,int length) throws IOException {check(length);out.write(value,off,length);}
                    private void check(int size) {if(Thread.currentThread().isInterrupted()) {throw new BusinessException(DEPENDENCY_UNAVAILABLE);}if(size>limit-count[0]) {throw new BusinessException(RESOURCE_LIMIT);}count[0]+=size;}
                    @Override public void close() throws IOException {flush();}
                };
                writer.write(bounded);bounded.flush();channel.force(true);
            }
            String hash=HexFormat.of().formatHex(digest.digest());
            if(expectedDigest!=null && !expectedDigest.equals(hash)) {throw new IllegalStateException("Fixed snapshot digest changed");}
            if(Files.exists(published,NOFOLLOW_LINKS)) {
                if(Files.size(published)==count[0] && hash.equals(hash(published))) {return new Published(hash,count[0]);}
                // 仅移动已核验根目录下的服务器键；不覆盖或删除损坏原件。
                Path quarantine=directory("quarantine").resolve(taskId+"-"+UUID.randomUUID()+".corrupt");
                Files.move(published,quarantine,StandardCopyOption.ATOMIC_MOVE);
            }
            Files.move(temp,published,StandardCopyOption.ATOMIC_MOVE);
            return new Published(hash,count[0]);
        } finally {if(Files.exists(temp,NOFOLLOW_LINKS)) {rejectLinks(temp);Files.delete(temp);}}
    }
    /** 输出前校验完整长度/摘要；打开同一只读句柄再校验，避免校验后切换路径。 */
    public InputStream open(FileEntity file,Runnable authorize) throws IOException {
        authorize.run();Path path=target(file.storageKey());
        if(file.state()!=FileState.READY) {throw new BusinessException(FILE_NOT_READY);}
        FileChannel channel;
        try {channel=FileChannel.open(path,READ,NOFOLLOW_LINKS);}catch(NoSuchFileException missing) {throw new BusinessException(FILE_RECOVERY_REQUIRED);}
        try {
            if(file.byteSize()==null || channel.size()!=file.byteSize() || channel.size()>limit) {throw new BusinessException(FILE_RECOVERY_REQUIRED);}
            MessageDigest digest=sha();var buffer=java.nio.ByteBuffer.allocate(65536);
            while(channel.read(buffer)!=-1) {buffer.flip();digest.update(buffer);buffer.clear();}
            if(!HexFormat.of().formatHex(digest.digest()).equals(file.sha256())) {throw new BusinessException(FILE_RECOVERY_REQUIRED);}
            channel.position(0);authorize.run();InputStream input=Channels.newInputStream(channel);
            return new FilterInputStream(input) {
                @Override public int read(byte[] bytes,int off,int len) throws IOException {authorize.run();return super.read(bytes,off,Math.min(len,65536));}
                @Override public int read() throws IOException {authorize.run();return super.read();}
            };
        } catch(IOException|RuntimeException error) {channel.close();throw error;}
    }
    private String hash(Path path) throws IOException {
        if(!Files.isRegularFile(path,NOFOLLOW_LINKS) || Files.size(path)>limit) {return "invalid";}
        MessageDigest digest=sha();try(InputStream input=Files.newInputStream(path,NOFOLLOW_LINKS)) {byte[] bytes=new byte[65536];int size;while((size=input.read(bytes))!=-1) {digest.update(bytes,0,size);}}return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest sha() {try {return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException error) {throw new IllegalStateException(error);}}
}
