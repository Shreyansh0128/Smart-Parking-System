package com.smartparking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class ParkingAutoAllocationStartup {
    private static final Logger logger = LoggerFactory.getLogger(ParkingAutoAllocationStartup.class);
    private final ParkingWorkflowService workflowService;

    public ParkingAutoAllocationStartup(ParkingWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void allocateWaitingRequests() {
        try {
            int allocated = workflowService.autoAllocatePendingRequests().size();
            if (allocated > 0) logger.info("Automatically allocated {} waiting parking request(s) on startup", allocated);
        } catch (DataAccessException exception) {
            logger.warn("Startup auto-allocation was skipped because the parking database is unavailable: {}",
                    exception.getMessage());
        }
    }
}
