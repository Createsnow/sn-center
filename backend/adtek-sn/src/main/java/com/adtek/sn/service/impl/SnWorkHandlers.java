package com.adtek.sn.service.impl;
import com.adtek.common.core.Actor;
import com.adtek.framework.task.TaskHandler;
import com.adtek.framework.task.domain.TaskEntity;
import org.springframework.context.annotation.*;
/** 三个操作有独立权限和幂等键空间，共用已验证的事务执行器，不能互相冒充操作类型。 */
@Configuration
public class SnWorkHandlers {
 @Bean TaskHandler piInitializationHandler(SnWorkEngine e){return handler(e,"PI_INIT");}
 @Bean TaskHandler snGenerationHandler(SnWorkEngine e){return handler(e,"GENERATE");}
 @Bean TaskHandler snAllocationHandler(SnWorkEngine e){return handler(e,"ALLOCATE");}
 private TaskHandler handler(SnWorkEngine e,String kind){return new TaskHandler(){
  public String type(){return SnWorkEngine.type(kind);}public String action(){return SnWorkEngine.permission(kind);}
  public void authorize(Actor actor,TaskEntity task){e.authorize(actor,kind);var work=e.rows.find(task.id());if(work==null||!kind.equals(work.kind()))throw new IllegalStateException("Missing SN task input");}
  public String execute(TaskEntity task,Actor actor){return e.execute(task,actor);}
  public void failed(TaskEntity task,int code){e.ended(task,"FAILED",code);}public void cancelled(TaskEntity task){e.ended(task,"CANCELLED",null);}
 };}
}
