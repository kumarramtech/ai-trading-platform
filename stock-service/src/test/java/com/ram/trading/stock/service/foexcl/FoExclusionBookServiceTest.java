package com.ram.trading.stock.service.foexcl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoExclusionBookServiceTest {

    private static final String HEAD = "TradDt,BizDt,Sgmt,Src,FinInstrmTp,FinInstrmId,ISIN,TckrSymb,SctySrs,XpryDt,"
            + "FininstrmActlXpryDt,StrkPric,OptnTp,FinInstrmNm,OpnPric\n";

    private static String row(String type, String sym, String expiry) {
        return "2026-11-19,2026-11-19,FO,NSE," + type + ",1,," + sym + ",," + expiry + "," + expiry + ",,,X,1\n";
    }

    /** 60 normal stocks with three expiries, plus IEX/IREDA (November only) and INOXWIND (Nov + Dec), like 19 Nov 2026. */
    private static String bhav() {
        StringBuilder sb = new StringBuilder(HEAD);
        for (int i = 0; i < 60; i++) {
            for (String x : new String[]{"2026-11-23", "2026-12-29", "2027-01-25"}) {
                sb.append(row("STF", "S" + i, x));
            }
        }
        sb.append(row("STF", "IEX", "2026-11-23")).append(row("STF", "IREDA", "2026-11-23"));
        sb.append(row("STF", "INOXWIND", "2026-11-23")).append(row("STF", "INOXWIND", "2026-12-29"));
        sb.append(row("STO", "IEX", "2026-11-23")).append(row("IDF", "NIFTY", "2026-11-23")); // options / index rows ignored
        return sb.toString();
    }

    @Test
    void findsTheStocksBeingRemovedAndTheirLastFuturesDay() throws Exception {
        Map<String, TreeSet<LocalDate>> fut = FoExclusionBookService.stockFutures(new StringReader(bhav()));
        assertEquals(63, fut.size());
        Map<String, LocalDate> removed = FoExclusionBookService.beingRemoved(fut);
        assertEquals(3, removed.size());
        assertEquals(LocalDate.of(2026, 11, 23), removed.get("IEX"));
        assertEquals(LocalDate.of(2026, 11, 23), removed.get("IREDA"));
        assertEquals(LocalDate.of(2026, 12, 29), removed.get("INOXWIND"));
    }

    @Test
    void anIncompleteFileFindsNothing() throws Exception {
        String small = HEAD + row("STF", "AAA", "2026-11-23") + row("STF", "AAA", "2026-12-29") + row("STF", "BBB", "2026-11-23");
        assertTrue(FoExclusionBookService.beingRemoved(FoExclusionBookService.stockFutures(new StringReader(small))).isEmpty());
    }

    @Test
    void aStockAddedMidMonthWithOnlyTheLaterSeriesIsNotARemoval() throws Exception {
        StringBuilder sb = new StringBuilder(bhav());
        sb.append(row("STF", "NEWSTOCK", "2026-12-29")).append(row("STF", "NEWSTOCK", "2027-01-25"));
        Map<String, LocalDate> removed = FoExclusionBookService.beingRemoved(
                FoExclusionBookService.stockFutures(new StringReader(sb.toString())));
        assertTrue(!removed.containsKey("NEWSTOCK"));
        assertEquals(3, removed.size());
    }

    @Test
    void shortResultAndTurnover() throws Exception {
        double[] win = FoExclusionBookService.shortResult(100, 500, 490, 25000, 25000);
        assertEquals(1000, win[0], 1e-9);
        assertEquals(65, win[1], 1e-9); // 0.13% of Rs 50,000
        assertEquals(935, win[2], 1e-9);
        assertEquals(2.0 - 0.13, win[3], 1e-9);
        double[] flatWhileNiftyFalls = FoExclusionBookService.shortResult(100, 500, 500, 25000, 24750);
        assertEquals(-1.0 - 0.13, flatWhileNiftyFalls[3], 1e-9); // a short that stays flat while NIFTY drops 1% lags NIFTY

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            sb.append(i == 0 ? "" : ",").append("[\"t\",0,0,0,200,500000]");
        }
        assertEquals(1e8, FoExclusionBookService.avgTurnover(new ObjectMapper().readTree(sb.append("]").toString())), 1e-3);
    }
}
