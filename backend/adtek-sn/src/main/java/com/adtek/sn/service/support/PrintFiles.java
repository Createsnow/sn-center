package com.adtek.sn.service.support;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.task.FileStorage;
import com.adtek.sn.domain.PrintEntity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/** 固定快照的两种产物；CSV原样保存业务编码，电子表格使用全文本XLSX避免公式/精度解释。 */
public final class PrintFiles {
 private PrintFiles(){}
 public static final String XLSX="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
 public static final List<String> HEADER=List.of("ordinal","sn","seq_text","seq_dec","seq_pi","pi","factory_code","customer","material","source_bill_no","source_system","source_account","source_bill_id","source_entry_id");
 public static List<String> cells(long ordinal,AcquireSnapshot s){return Arrays.asList(Long.toString(ordinal),s.sn(),s.seqText(),s.seqDec(),s.seqPi(),s.pi(),s.factoryCode(),s.customer(),s.material(),s.billNo(),s.sourceSystem(),s.sourceAccount(),s.sourceBillId(),s.sourceEntryId());}
 public static void csvRow(OutputStream output,List<String> cells) throws IOException {var line=new StringBuilder();for(int index=0;index<cells.size();index++){if(index>0)line.append(',');String value=cells.get(index);line.append('"').append(value==null?"":value.replace("\"","\"\"")).append('"');}line.append("\r\n");output.write(line.toString().getBytes(StandardCharsets.UTF_8));}
 @FunctionalInterface public interface Rows {void each(Consumer<List<String>> row);}
 public static void xlsx(OutputStream output,PrintEntity print,long byteLimit,Rows rows) throws Exception { xlsx(output,print.createdAt(),HEADER,byteLimit,rows); }
 public static void xlsx(OutputStream output,java.time.Instant createdAt,List<String> header,long byteLimit,Rows rows) throws Exception {
  var bytes=new ByteArrayOutputStream();
  // POI只保留200行，压缩临时sheet，close在失败/成功都释放临时资源；最终ZIP也有独立字节准入。
  try(var workbook=new SXSSFWorkbook(200)){workbook.setCompressTempFiles(true);workbook.setZip64Mode(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded);var sheet=workbook.createSheet("SN");var text=workbook.createCellStyle();text.setDataFormat(workbook.createDataFormat().getFormat("@"));int[] ordinal={0};
   Consumer<List<String>> write=values->{if(ordinal[0]>=1048576)throw new BusinessException(com.adtek.framework.constant.ErrorCodeConstants.RESOURCE_LIMIT);var row=sheet.createRow(ordinal[0]++);for(int col=0;col<values.size();col++){var cell=row.createCell(col,org.apache.poi.ss.usermodel.CellType.STRING);cell.setCellStyle(text);cell.setCellValue(values.get(col)==null?"":values.get(col));}};
   write.accept(header);rows.each(write);workbook.write(new FilterOutputStream(bytes){long written;@Override public void write(int b)throws IOException{check(1);out.write(b);}@Override public void write(byte[] b,int o,int n)throws IOException{check(n);out.write(b,o,n);}private void check(int n){if((written+=n)>byteLimit)throw new BusinessException(com.adtek.framework.constant.ErrorCodeConstants.RESOURCE_LIMIT);}});
  }
  // ZIP时间和核心属性标准化，恢复同一快照必须产生相同摘要，不能让打包时间改变历史文件。
  try(var input=new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()));var zip=new ZipOutputStream(output)){
   ZipEntry entry;byte[] buffer=new byte[65536];while((entry=input.getNextEntry())!=null){var fixed=new ZipEntry(entry.getName());fixed.setTime(0);zip.putNextEntry(fixed);
    if("docProps/core.xml".equals(entry.getName())){String time=createdAt.toString();String xml="<?xml version=\"1.0\" encoding=\"UTF-8\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:creator>Adtek</dc:creator><dcterms:created xsi:type=\"dcterms:W3CDTF\">"+time+"</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">"+time+"</dcterms:modified></cp:coreProperties>";zip.write(xml.getBytes(StandardCharsets.UTF_8));}
    else{int count;while((count=input.read(buffer))!=-1)zip.write(buffer,0,count);}zip.closeEntry();input.closeEntry();}
   zip.finish();
  }
 }
}


