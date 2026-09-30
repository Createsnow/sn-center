package com.adtek.sn.service.impl;
import com.adtek.common.core.Actor;
import com.adtek.framework.task.TaskHandler;
import com.adtek.framework.task.domain.TaskEntity;
import com.adtek.sn.service.support.TransferPolicy;
import org.springframework.context.annotation.*;

/** 五类动作独立操作唯一键/授权，共同使用任务代次和事务护栏。 */
@Configuration public class TransferHandlers {
    @Bean TaskHandler transferApply(TransferEngine e){return handler(e,"APPLY");}
    @Bean TaskHandler transferDirect(TransferEngine e){return handler(e,"DIRECT");}
    @Bean TaskHandler transferApprove(TransferEngine e){return handler(e,"APPROVE");}
    @Bean TaskHandler transferReject(TransferEngine e){return handler(e,"REJECT");}
    @Bean TaskHandler transferWithdraw(TransferEngine e){return handler(e,"WITHDRAW");}
    private TaskHandler handler(TransferEngine e,String kind){return new TaskHandler(){
        public String type(){return TransferEngine.type(kind);}
        public String action(){return TransferPolicy.permission(kind);}
        public void authorize(Actor actor,TaskEntity task){e.authorize(actor,task,kind);}
        public String execute(TaskEntity task,Actor actor){return e.execute(task,actor);}
        public void failed(TaskEntity task,int code){e.ended(task,"FAILED",code);}
        public void cancelled(TaskEntity task){e.ended(task,"CANCELLED",null);}
    };}
}
