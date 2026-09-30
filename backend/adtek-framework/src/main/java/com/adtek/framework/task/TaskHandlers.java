package com.adtek.framework.task;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class TaskHandlers {
    private final Map<String,TaskHandler> handlers;
    public TaskHandlers(List<TaskHandler> values) {
        Map<String,TaskHandler> registered=new HashMap<>();
        for(var handler:values) { if(registered.put(handler.type(),handler)!=null) { throw new IllegalStateException("Duplicate task handler"); } }
        handlers=Map.copyOf(registered);
    }
    public TaskHandler require(String type) { var handler=handlers.get(type);if(handler==null) { throw new IllegalStateException("Unregistered task type"); }return handler; }
}
