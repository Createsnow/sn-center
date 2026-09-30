package com.adtek.server;

import com.adtek.sn.dto.MaintenanceCommandDTO;

import org.springframework.scheduling.support.CronExpression;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** UTC日内幂等维护计划；cron仅决定唤醒时刻，时区必须显式且不接受系统默认值。 */
record MaintenanceSchedule(
        CronExpression cron, ZoneId zone, int monthsAhead, int rowLimit, boolean archiveCopy) {
    static MaintenanceSchedule parse(
            String expression, String zone, int ahead, int limit, String archive) {
        if (expression == null
                || zone == null
                || !Set.of("true", "false").contains(archive)
                || ahead < 1
                || ahead > 12
                || limit < 1
                || limit > 1000000)
            throw new IllegalArgumentException("MAINTENANCE_SCHEDULE_INVALID");
        return new MaintenanceSchedule(
                CronExpression.parse(expression),
                ZoneId.of(zone),
                ahead,
                limit,
                Boolean.parseBoolean(archive));
    }

    Instant next(Instant after) {
        var next = cron.next(after.atZone(zone));
        if (next == null) throw new IllegalArgumentException("MAINTENANCE_SCHEDULE_NO_NEXT");
        return next.toInstant();
    }

    List<MaintenanceCommandDTO> commands(Instant now, boolean apply) {
        var utc = now.atZone(ZoneOffset.UTC);
        var month = YearMonth.from(utc);
        var actions =
                new ArrayList<>(
                        List.of(
                                MaintenanceCommandDTO.Action.PARTITIONS,
                                MaintenanceCommandDTO.Action.CLEAN_PREVIEWS));
        if (archiveCopy) actions.add(MaintenanceCommandDTO.Action.ARCHIVE_COPY);
        var commands = new ArrayList<MaintenanceCommandDTO>();
        for (var action : actions) {
            var target =
                    action == MaintenanceCommandDTO.Action.ARCHIVE_COPY
                            ? month.minusMonths(1)
                            : month;
            String input =
                    "schedule-v1|"
                            + utc.toLocalDate()
                            + "|"
                            + cron
                            + "|"
                            + zone
                            + "|"
                            + action
                            + "|"
                            + target
                            + "|"
                            + monthsAhead
                            + "|"
                            + rowLimit;
            String run = UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8)).toString();
            commands.add(
                    new MaintenanceCommandDTO(action, run, apply, target, monthsAhead, rowLimit));
        }
        return List.copyOf(commands);
    }
}
