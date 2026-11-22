package com.company.floorplan.release.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q9 建议口径的纯函数单测：接收方按释放项解析 + 两类主用类型。
 * 不依赖 Spring / 数据库，可直接穷举。
 */
class CustodianResolutionTest {

    @Test
    void explicitPerItemOverridesPrimary() {
        Map<String, Long> byItem = Map.of("VEHICLE", 11L, "CERTIFICATE", 22L);
        assertEquals(11L, CustodianResolver.resolve(ReleaseItemType.VEHICLE, 99L, byItem));
        assertEquals(22L, CustodianResolver.resolve(ReleaseItemType.CERTIFICATE, 99L, byItem));
    }

    @Test
    void inheritsPrimaryWhenItemNotSpecified() {
        assertEquals(99L, CustodianResolver.resolve(
                ReleaseItemType.KEY, 99L, Map.of("CERTIFICATE", 22L)));
    }

    @Test
    void keyMatchingIsCaseInsensitiveAndTrimmed() {
        assertEquals(7L, CustodianResolver.resolve(
                ReleaseItemType.VEHICLE, null, Map.of(" vehicle ", 7L)));
        assertEquals(8L, CustodianResolver.resolve(
                ReleaseItemType.CERTIFICATE, null, Map.of("Certificate", 8L)));
    }

    @Test
    void nullMapAndNullPrimaryYieldNull() {
        assertNull(CustodianResolver.resolve(ReleaseItemType.VEHICLE, null, null));
        assertNull(CustodianResolver.resolve(ReleaseItemType.VEHICLE, null, Map.of()));
    }

    @Test
    void nullTypeReturnsPrimary() {
        assertEquals(5L, CustodianResolver.resolve(null, 5L, Map.of("VEHICLE", 9L)));
    }

    @Test
    void certificateGoesToDepositoryLine() {
        assertEquals(CustodianType.CERTIFICATE_DEPOSITORY,
                ReleaseItemType.CERTIFICATE.defaultCustodianType());
        assertEquals(CustodianType.CERTIFICATE_DEPOSITORY,
                CustodianResolver.expectedType(ReleaseItemType.CERTIFICATE));
    }

    @Test
    void vehicleAndKeyGoToDealerLine() {
        assertEquals(CustodianType.DEALER_WAREHOUSE,
                ReleaseItemType.VEHICLE.defaultCustodianType());
        assertEquals(CustodianType.DEALER_WAREHOUSE,
                ReleaseItemType.KEY.defaultCustodianType());
    }

    @Test
    void onlyTwoPrimaryTypesAndTheyNeedNoWrittenApproval() {
        long primaryCount = java.util.Arrays.stream(CustodianType.values())
                .filter(CustodianType::isPrimary).count();
        assertEquals(2L, primaryCount);
        assertTrue(CustodianType.DEALER_WAREHOUSE.isPrimary());
        assertTrue(CustodianType.CERTIFICATE_DEPOSITORY.isPrimary());
        assertFalse(CustodianType.DEALER_WAREHOUSE.requiresWrittenApproval());
    }

    @Test
    void legacyThirdPartyTypesRequireWrittenApproval() {
        assertTrue(CustodianType.THIRD_PARTY_WAREHOUSE.requiresWrittenApproval());
        assertTrue(CustodianType.ON_SITE_SUPERVISOR.requiresWrittenApproval());
        assertFalse(CustodianType.ON_SITE_SUPERVISOR.isPrimary());
    }
}
