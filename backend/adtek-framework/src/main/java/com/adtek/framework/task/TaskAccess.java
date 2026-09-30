package com.adtek.framework.task;
import com.adtek.common.core.*;
import com.adtek.framework.security.*;
import com.adtek.framework.task.domain.TaskEntity;
import java.util.UUID;
import org.springframework.stereotype.Component;
import static com.adtek.framework.constant.ErrorCodeConstants.*;
@Component
public class TaskAccess {
    private final CurrentActor current;private final AuthorizationPort authorization;private final TaskHandlers handlers;
    public TaskAccess(CurrentActor current,AuthorizationPort authorization,TaskHandlers handlers) {this.current=current;this.authorization=authorization;this.handlers=handlers;}
    /** HTTP只从已验证上下文取得主体；跨主体与缺对象统一404，不凭UUID保密性授权。 */
    public Actor require(TaskEntity task) { return require(task,current.requireAuthenticated().userId()); }
    public Actor require(TaskEntity task,long userId) {
        if(task==null || task.userId()!=userId) {throw new BusinessException(com.adtek.common.constant.ErrorCodeConstants.NOT_FOUND);}
        Actor actor=fresh(userId);handlers.require(task.taskType()).authorize(actor,task);return actor;
    }
    public Actor fresh(long id) {
        Actor actor=authorization.loadCurrent(id).orElseThrow(()->new BusinessException(FORBIDDEN));
        if(actor.mustChangePassword()) {throw new BusinessException(PASSWORD_CHANGE_REQUIRED);}return actor;
    }
    public Actor file(TaskEntity task){return file(task,current.requireAuthenticated());}
    public Actor file(TaskEntity task,Actor requested){
        if(task==null)throw new BusinessException(com.adtek.common.constant.ErrorCodeConstants.NOT_FOUND);
        var handler=handlers.require(task.taskType());
        if(task.userId()!=requested.userId()&&!handler.sharedFileAccess())throw new BusinessException(com.adtek.common.constant.ErrorCodeConstants.NOT_FOUND);
        var actor=fresh(requested.userId());if(actor.securityVersion()!=requested.securityVersion())throw new BusinessException(FORBIDDEN);
        handler.authorizeFile(actor,task);return actor;
    }
    public static String uuid(String value) {
        try { if(value==null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {throw new IllegalArgumentException();}return UUID.fromString(value).toString(); }
        catch(IllegalArgumentException error) {throw new BusinessException(com.adtek.common.constant.ErrorCodeConstants.INVALID_ARGUMENT);}
    }
}
