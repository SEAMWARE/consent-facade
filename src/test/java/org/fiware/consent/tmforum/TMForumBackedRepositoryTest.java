package org.fiware.consent.tmforum;

import org.fiware.consent.tmforum.agreement.model.AgreementVO;
import org.fiware.consent.tmforum.agreement.model.CharacteristicVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TMForumBackedRepository#getCharacteristicValue}, the accessor that reads
 * the contract policy (and the other characteristics) the EDC extension writes onto an agreement.
 */
class TMForumBackedRepositoryTest {

    private static AgreementVO agreementWithPolicy() {
        Map<String, Object> policy = Map.of("@type", "Set", "uid", "urn:policy:1");
        return new AgreementVO().characteristic(List.of(
                new CharacteristicVO().name(TMForumBackedRepository.AgreementCharacteristic.ASSET_ID).value("asset-1"),
                new CharacteristicVO().name(TMForumBackedRepository.AgreementCharacteristic.POLICY).value(policy),
                new CharacteristicVO().name(TMForumBackedRepository.AgreementCharacteristic.SIGNING_DATE).value(42L)));
    }

    static Stream<Arguments> characteristicLookups() {
        Map<String, Object> expectedPolicy = Map.of("@type", "Set", "uid", "urn:policy:1");
        return Stream.of(
                Arguments.of(TMForumBackedRepository.AgreementCharacteristic.POLICY, expectedPolicy),
                Arguments.of(TMForumBackedRepository.AgreementCharacteristic.ASSET_ID, "asset-1"),
                Arguments.of(TMForumBackedRepository.AgreementCharacteristic.SIGNING_DATE, 42L));
    }

    @ParameterizedTest
    @MethodSource("characteristicLookups")
    void getCharacteristicValue_returnsValueForKnownCharacteristic(String characteristicName, Object expectedValue) {
        Optional<Object> value = TMForumBackedRepository.getCharacteristicValue(agreementWithPolicy(), characteristicName);
        assertEquals(Optional.of(expectedValue), value,
                "The value of characteristic '" + characteristicName + "' should be returned.");
    }

    @Test
    void getCharacteristicValue_isEmptyForUnknownCharacteristic() {
        Optional<Object> value = TMForumBackedRepository.getCharacteristicValue(
                agreementWithPolicy(), TMForumBackedRepository.AgreementCharacteristic.PROVIDER_ID);
        assertTrue(value.isEmpty(), "An absent characteristic should yield an empty result.");
    }

    @Test
    void getCharacteristicValue_isEmptyWhenNoCharacteristics() {
        Optional<Object> value = TMForumBackedRepository.getCharacteristicValue(
                new AgreementVO(), TMForumBackedRepository.AgreementCharacteristic.POLICY);
        assertTrue(value.isEmpty(), "An agreement without characteristics should yield an empty result.");
    }
}
