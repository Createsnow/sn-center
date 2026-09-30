package com.adtek.server;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MaintenanceScheduleTest {
    private MaintenanceSchedule daily(){return MaintenanceSchedule.parse("0 15 2 * * *","Asia/Shanghai",3,100000,"true");}
    @Test void computesConfiguredTimezoneAndClosedUtcMonth(){
        var now=Instant.parse("2026-09-30T20:00:00Z");var schedule=daily();
        assertThat(schedule.next(now)).isEqualTo(Instant.parse("2026-10-01T18:15:00Z"));
        var actions=schedule.commands(now,true);assertThat(actions).hasSize(3);
        assertThat(actions.getLast().month()).isEqualTo(YearMonth.of(2026,8));
        assertThat(actions).noneMatch(x->x.action().name().equals("ARCHIVE_FINALIZE"));
    }
    @Test void retriesSameUtcDayButNewDayGetsNewRun(){
        var schedule=daily();var now=Instant.parse("2026-09-30T10:00:00Z");
        assertThat(schedule.commands(now,true)).isEqualTo(schedule.commands(now.plusSeconds(3600),true));
        assertThat(schedule.commands(now,true).getFirst().runId()).isNotEqualTo(schedule.commands(now.plusSeconds(86400),true).getFirst().runId());
        assertThat(schedule.commands(now,false)).allMatch(x->!x.apply());
    }
    @Test void invalidCronTimezoneAndConfigurationNeverSilentlyFallBack(){
        assertThatThrownBy(()->MaintenanceSchedule.parse("invalid","UTC",3,100,"false")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->MaintenanceSchedule.parse("0 0 2 * * *","Mars/Unknown",3,100,"false")).isInstanceOf(DateTimeException.class);
        assertThatThrownBy(()->MaintenanceSchedule.parse("0 0 2 * * *","UTC",0,100,"false")).hasMessage("MAINTENANCE_SCHEDULE_INVALID");
        assertThatThrownBy(()->MaintenanceSchedule.parse("0 0 2 * * *","UTC",3,100,"yes")).hasMessage("MAINTENANCE_SCHEDULE_INVALID");
        assertThatThrownBy(()->MaintenanceSchedule.parse("0 0 2 31 2 *","UTC",3,100,"false").next(Instant.now())).hasMessage("MAINTENANCE_SCHEDULE_NO_NEXT");
    }
}
