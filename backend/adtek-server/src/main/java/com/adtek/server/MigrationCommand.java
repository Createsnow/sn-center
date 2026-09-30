package com.adtek.server;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;

/** 单次Flyway命令；容器页面进程可以完全不挂载DDL密码。只允许显式schema，不自动baseline/clean。 */
final class MigrationCommand {
    private MigrationCommand() {}
    static int run(String[] args){
        try{
            if(args.length>2||(args.length==2&&!"--apply".equals(args[1])))throw new IllegalArgumentException();
            String url=required("ADTEK_DB_URL"),user=required("ADTEK_MIGRATION_USER"),password=required("ADTEK_MIGRATION_PASSWORD"),schema=required("ADTEK_MIGRATION_ALLOWED_SCHEMA");
            if(!schema.matches("[A-Za-z0-9_]{1,64}"))throw new IllegalArgumentException();
            try(var c=DriverManager.getConnection(url,user,password)){
                if(!schema.equals(c.getCatalog()))throw new IllegalArgumentException();
            }
            var flyway=Flyway.configure().dataSource(url,user,password).cleanDisabled(true).baselineOnMigrate(false).validateOnMigrate(true).load();
            if(args.length==2){var result=flyway.migrate();System.out.println("MIGRATION_APPLIED count="+result.migrationsExecuted);}
            else {var result=flyway.validateWithResult();if(!result.validationSuccessful)throw new IllegalStateException();System.out.println("MIGRATION_VALIDATED");}
            return 0;
        }catch(Exception failure){System.err.println("MIGRATION_REFUSED_OR_FAILED");return 2;}
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException();return value;}
}
