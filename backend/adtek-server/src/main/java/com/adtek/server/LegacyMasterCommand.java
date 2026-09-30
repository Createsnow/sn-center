package com.adtek.server;
import com.adtek.sn.config.LegacyMasterFactory;
import com.adtek.sn.dto.LegacyMasterCommandDTO;
import com.adtek.system.config.LegacyIdentityFactory;
import java.nio.file.Path;
import java.util.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
/** 主数据离线入口；server只装配模块端口，身份和SN写入各归其所属模块。 */
final class LegacyMasterCommand {
    private LegacyMasterCommand(){}
    static int run(String[] args){
        try{
            var options=new HashMap<String,String>();boolean apply=false;
            for(int i=1;i<args.length;i++){
                if("--apply".equals(args[i])&&!apply){apply=true;continue;}
                if(!Set.of("--run-id","--snapshot-id").contains(args[i])||i+1>=args.length||options.put(args[i],args[++i])!=null)throw new IllegalArgumentException();
            }
            var command=new LegacyMasterCommandDTO(options.get("--run-id"),options.get("--snapshot-id"),apply);
            var target=new DriverManagerDataSource(required("ADTEK_LEGACY_TARGET_URL"),required("ADTEK_LEGACY_TARGET_USER"),required("ADTEK_LEGACY_TARGET_PASSWORD"));
            var result=LegacyMasterFactory.create(target,required("ADTEK_LEGACY_TARGET_SCHEMA"),Path.of(required("ADTEK_LEGACY_SNAPSHOT_ROOT")),LegacyIdentityFactory.create()).promote(command);
            System.out.println(new ObjectMapper().writeValueAsString(result));return 0;
        }catch(Exception failure){String code=failure.getMessage();if(code==null||!code.matches("LEGACY_[A-Z0-9_]+"))code="LEGACY_MASTER_FAILED";System.err.println(code);return 2;}
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException("LEGACY_ENVIRONMENT_MISSING");return value;}
}
