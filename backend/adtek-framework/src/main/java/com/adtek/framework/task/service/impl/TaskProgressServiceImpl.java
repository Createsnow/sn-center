package com.adtek.framework.task.service.impl;

import com.adtek.framework.task.mapper.DurableTaskMapper;
import com.adtek.framework.task.service.TaskProgressService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class TaskProgressServiceImpl implements TaskProgressService {
    private final DurableTaskMapper tasks;

    public TaskProgressServiceImpl(DurableTaskMapper tasks) {
        this.tasks = tasks;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(String id, long epoch, long processed, String phase) {
        if (epoch < 1
                || processed < 0
                || !java.util.Set.of("WRITING", "PUBLISHED").contains(phase)) {
            throw new IllegalArgumentException("Invalid task progress");
        }
        tasks.progressWrite(id, epoch, processed, phase);
    }
}
