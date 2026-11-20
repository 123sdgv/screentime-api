package dev.gray.screentime

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AccountingTest {
    private val utc = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 1, 1)
    private val midnight = day.start(utc)
    private fun ev(t: Long, pkg: String, kind: Kind, name: String = "Main") = Signal(midnight + t, pkg, name, kind)

    @Test fun activityHandoffAndRepeatedResumesCountOnce() {
        val result = reduceEvents(listOf(ev(100,"A",Kind.RESUME),ev(150,"A",Kind.RESUME),
            ev(500,"A",Kind.PAUSE),ev(520,"A",Kind.RESUME,"Detail"),ev(800,"A",Kind.PAUSE,"Detail")), utc)
        assertEquals(Evidence(680, 1),result.records[Key("A",day)])
    }
    @Test fun returnFromAnotherAppCountsEvenWhenQuick() {
        val r = reduceEvents(listOf(ev(0,"A",Kind.RESUME),ev(400,"A",Kind.PAUSE),ev(410,"B",Kind.RESUME),
            ev(600,"B",Kind.PAUSE),ev(610,"A",Kind.RESUME),ev(900,"A",Kind.PAUSE)),utc)
        assertEquals(Evidence(690,2),r.records[Key("A",day)])
        assertEquals(Evidence(190,1),r.records[Key("B",day)])
    }
    @Test fun overlappingActivitiesUseUnionNotSum() {
        val r = reduceEvents(listOf(ev(0,"A",Kind.RESUME),ev(20,"A",Kind.RESUME,"Detail"),
            ev(100,"A",Kind.PAUSE),ev(150,"A",Kind.PAUSE,"Detail")),utc)
        assertEquals(Evidence(150,1),r.records[Key("A",day)])
    }
    @Test fun screenOffClosesAndBootDoesNotChargePoweredOffTime() {
        val r = reduceEvents(listOf(ev(0,"A",Kind.RESUME),ev(100,"android",Kind.BARRIER),
            ev(200,"A",Kind.RESUME),ev(10_000,"android",Kind.BOOT)),utc)
        assertEquals(Evidence(100,2),r.records[Key("A",day)])
    }
    @Test fun crossYearGetsTwoDurationsAndOneEntry() {
        val r = reduceEvents(listOf(ev(-2000,"A",Kind.RESUME),ev(3000,"A",Kind.PAUSE)),utc)
        assertEquals(Evidence(2000,1),r.records[Key("A",day.minusDays(1))])
        assertEquals(Evidence(3000,0),r.records[Key("A",day)])
    }
    @Test fun unfinishedSessionAcrossMidnightKeepsCoverageOpen() {
        val result = reduceEvents(listOf(ev(-500,"A",Kind.RESUME),ev(3000,"system",Kind.OTHER)),utc)
        assertEquals(midnight - 500,result.pendingFrom)
        assertNull(result.records[Key("A",day)])
    }
    @Test fun datesRespectDstAndLocalZone() {
        val ny=ZoneId.of("America/New_York")
        for ((d,hours) in listOf(LocalDate.of(2024,3,10) to 23,LocalDate.of(2024,11,3) to 25)) {
            val result=mutableMapOf<LocalDate,Long>()
            splitInterval(d.start(ny),d.plusDays(1).start(ny),ny){date,ms->result[date]=ms}
            assertEquals(mapOf(d to hours*3_600_000L),result)
        }
        assertEquals(day.minusDays(1),midnight.dateIn(ny))
    }
    @Test fun expandedBucketsAndEmptyOrIncompleteMetricsStayUnknown() {
        assertNull(bucketDay(midnight-1,midnight+1,utc))
        assertEquals(day,bucketDay(midnight,day.plusDays(1).start(utc),utc))
        assertNull(metricValue(null,false,true))
        assertEquals(0L,metricValue(null,true,true))
        val row=Daily("p",day.toString(),3600L,null,"system","UTC")
        assertNull(metricValue(row,false,false))
        assertNull(metricValue(row.copy(ms=null,entries=1),true,true))
    }
    @Test fun repeatedWindowsKeepOneBestObservationPerMetric() {
        val events=DayValue(2000L,3,"events"); val system=DayValue(1800L,null,"system")
        val merged=bestEvidence(events,system)
        assertEquals(DayValue(1800,3,"system"),merged)
        assertEquals(merged,bestEvidence(merged,events))
        assertEquals(merged,bestEvidence(merged,merged))
    }
    @Test fun aGapCannotBecomeACompleteDay() {
        assertFalse(unionSpan(Span(0,500),Span(600,1000)).covers(0,1000))
        assertTrue(unionSpan(Span(0,720),Span(600,1000)).covers(0,1000))
    }
    @Test fun calendarContainsEachCivilDateExactlyOnce() {
        for (year in listOf(2012,2024,2026,2027)) {
            val dates=calendarWeeks(year).flatten().filterNotNull()
            val first=LocalDate.of(year,1,1)
            assertEquals((0 until first.lengthOfYear()).map { first.plusDays(it.toLong()) },dates)
            assertTrue(calendarWeeks(year).all { it.size == 7 })
        }
        assertEquals(54,calendarWeeks(2012).size)
    }
    @Test fun rankingAndActiveDayUnionUsePositiveDurationsAndStablePackages() {
        val rows=listOf(Daily("a","2026-01-01",2000L,1,"events","UTC"),
            Daily("b","2026-01-01",100L,4,"events","UTC"),Daily("b","2026-01-02",200L,2,"events","UTC"),
            Daily("a","2026-01-03",null,1,"events","UTC"))
        val apps=totals(rows,emptyMap())
        assertEquals("a",ranked(apps,0).first().pkg)
        assertEquals("b",ranked(apps,1).first().pkg)
        assertEquals("b",ranked(apps,2).first().pkg)
        assertEquals(2,apps.flatMap { it.days }.toSet().size)
        assertEquals(1,longestStreak(setOf(day,day.plusDays(2))))
    }
    @Test fun tenRecordedDaysDoNotCreateZerosForTheRestOfAYear() {
        val covered=(0L..9).map { day.plusDays(it) }.toSet()
        val values=calendarWeeks(day.year).flatten().filterNotNull().map { metricValue(null,it in covered,false) }
        assertEquals(10,values.count { it == 0L })
        assertEquals(355,values.count { it == null })
    }
}
