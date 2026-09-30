package com.adtek.server;

import com.adtek.sn.config.SnMaintenanceFactory;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** 独立调度进程：启动补执行当日计划，失败非零退出交给外部调度监控；不会自动终结归档。 */
final class MaintenanceSchedulerCommand {
    private MaintenanceSchedulerCommand(){}
    static int run(String[] args){
        try{
            boolean once=false,apply=false;
            for(int i=1;i<args.length;i++){
                if("--once".equals(args[i])&&!once)once=true;
                else if("--apply".equals(args[i])&&!apply)apply=true;
                else throw new IllegalArgumentException();
            }
            var schedule=MaintenanceSchedule.parse(required("ADTEK_MAINT_CRON"),required("ADTEK_MAINT_ZONE"),
                    Integer.parseInt(System.getenv().getOrDefault("ADTEK_MAINT_MONTHS_AHEAD","3")),
                    Integer.parseInt(System.getenv().getOrDefault("ADTEK_MAINT_ROW_LIMIT","100000")),
                    System.getenv().getOrDefault("ADTEK_MAINT_ARCHIVE_COPY","false"));
            schedule.next(Instant.now()); // 不可满足的日历表达式必须在首次维护前拒绝。
            var source=new DriverManagerDataSource(required("ADTEK_MAINT_DB_URL"),required("ADTEK_MAINT_DB_USER"),required("ADTEK_MAINT_DB_PASSWORD"));
            var service=SnMaintenanceFactory.create(source,required("ADTEK_MAINT_ALLOWED_SCHEMA"));var json=new ObjectMapper();
            while(!Thread.currentThread().isInterrupted()){
                for(var command:schedule.commands(Instant.now(),apply))System.out.println(json.writeValueAsString(service.execute(command)));
                if(once)return 0;
                Instant next=schedule.next(Instant.now());
                System.out.println(json.writeValueAsString(Map.of("state","WAITING","nextAtUtc",next.toString(),"apply",apply)));
                while(Instant.now().isBefore(next))Thread.sleep(Math.max(1,Math.min(30000,Duration.between(Instant.now(),next).toMillis())));
            }
            return 0;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return 0;}
        catch(Exception failure){
            String code=failure.getMessage();if(code==null||!code.matches("(?:MAINTENANCE|PARTITION|ARCHIVE)_[A-Z0-9_]+"))code="MAINTENANCE_SCHEDULE_FAILED";
            System.err.println(code);return 2;
        }
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException("MAINTENANCE_ENVIRONMENT_MISSING");return value;}
}
