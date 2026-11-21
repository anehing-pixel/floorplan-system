package com.company.floorplan.infrastructure.persistence;

import com.company.floorplan.domain.service.BusinessCalendar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 营业日历实现：优先取 holidays 表；表中无记录时按"周一至周五为营业日"兜底，
 * 保证系统开箱即可运行（节假日可由接口批量导入）。
 */
@Component
public class JdbcBusinessCalendar implements BusinessCalendar {

    private final JdbcTemplate jdbc;

    public JdbcBusinessCalendar(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isBusinessDay(LocalDate date) {
        Boolean flag = jdbc.query(
                "SELECT is_business_day FROM holidays WHERE biz_date = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null,
                date);
        if (flag != null) {
            return flag;
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    @Override
    public LocalDate plusBusinessDays(LocalDate from, int n) {
        LocalDate cursor = from;
        int step = n >= 0 ? 1 : -1;
        int remaining = Math.abs(n);
        int guard = 0;
        while (remaining > 0 && guard++ < 3650) {
            cursor = cursor.plusDays(step);
            if (isBusinessDay(cursor)) {
                remaining--;
            }
        }
        return cursor;
    }
}
