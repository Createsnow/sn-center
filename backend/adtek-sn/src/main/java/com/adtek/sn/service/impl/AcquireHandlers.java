package com.adtek.sn.service.impl;
import com.adtek.common.core.Actor;
import com.adtek.framework.task.TaskHandler;
import com.adtek.framework.task.domain.TaskEntity;
import org.springframework.context.annotation.*;
/** 页面与MES只有入口协议不同，任务处理器/授权/事务共用。 */
@Configuration public class AcquireHandlers {
 @Bean TaskHandler acquisitionHandler(AcquireEngine engine){return handler(engine,"TAKE");}
 @Bean TaskHandler printConfirmationHandler(AcquireEngine engine){return handler(engine,"CALLBACK");}
 private TaskHandler handler(AcquireEngine e,String kind){return new TaskHandler(){
  public String type(){return AcquireEngine.type(kind);}public String action(){return AcquireEngine.permission(kind);}
  public void authorize(Actor actor,TaskEntity task){e.authorize(actor,task,kind);}
  public String execute(TaskEntity task,Actor actor){return e.execute(task,actor);}
  public void failed(TaskEntity task,int code){e.ended(task,"FAILED",code);}public void cancelled(TaskEntity task){e.ended(task,"CANCELLED",null);}
 };}
}
