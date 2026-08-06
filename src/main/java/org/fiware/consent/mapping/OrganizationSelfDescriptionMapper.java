package org.fiware.consent.mapping;

import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.consent.configuration.FacadeProperties;
import org.fiware.consent.model.AddressVO;
import org.fiware.consent.model.LegalPersonVO;
import org.fiware.consent.model.SelfDescriptionVO;
import org.fiware.consent.tmforum.party.model.CharacteristicVO;
import org.fiware.consent.tmforum.party.model.ContactMediumVO;
import org.fiware.consent.tmforum.party.model.MediumCharacteristicVO;
import org.fiware.consent.tmforum.party.model.OrganizationChildRelationshipVO;
import org.fiware.consent.tmforum.party.model.OrganizationIdentificationVO;
import org.fiware.consent.tmforum.party.model.OrganizationRefVO;
import org.fiware.consent.tmforum.party.model.OrganizationVO;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps a TM Forum {@link OrganizationVO} into the {@link SelfDescriptionVO} the consent-manager
 * reads while building consent receipts.
 *
 * <p>The receipt builder is load-bearing (see {@code REQUIREMENTS.md} §2.3): it dereferences each
 * party's self-description URL and reads {@code legalName}, {@code legalPerson.legalAddress} and
 * {@code legalPerson.subOrganization}. A missing legal address makes that call fail, so this mapper
 * always produces a {@link LegalPersonVO} with a (possibly empty) {@link AddressVO} and a non-null
 * {@code subOrganization} list.
 *
 * <p>The participant {@code did} is read from the party characteristic whose name is configured via
 * {@link FacadeProperties.PartyMapping#getDidCharacteristic()}.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor
public class OrganizationSelfDescriptionMapper {

    private final FacadeProperties facadeProperties;

    /**
     * Maps an organization into a participant self-description.
     *
     * @param organization the TM Forum organization
     * @return the participant self-description, or {@code null} if {@code organization} is {@code null}
     */
    public SelfDescriptionVO toSelfDescription(OrganizationVO organization) {
        if (organization == null) {
            return null;
        }
        return new SelfDescriptionVO()
                .did(did(organization).orElse(null))
                .legalName(legalName(organization).orElse(null))
                .legalPerson(new LegalPersonVO()
                        .registrationNumber(registrationNumber(organization).orElse(null))
                        .legalAddress(address(organization))
                        .headquartersAddress(address(organization))
                        .subOrganization(subOrganizations(organization)));
    }

    /**
     * Reads the participant did from the configured party characteristic.
     */
    private Optional<String> did(OrganizationVO organization) {
        String didCharacteristic = facadeProperties.getParty().getDidCharacteristic();
        return Optional.ofNullable(organization.getPartyCharacteristic())
                .orElse(List.of())
                .stream()
                .filter(characteristic -> Objects.equals(characteristic.getName(), didCharacteristic))
                .map(CharacteristicVO::getValue)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .findFirst();
    }

    /**
     * The organization's legal name, preferring {@code name} and falling back to {@code tradingName}.
     */
    private Optional<String> legalName(OrganizationVO organization) {
        return Optional.ofNullable(organization.getName())
                .or(() -> Optional.ofNullable(organization.getTradingName()));
    }

    private Optional<String> registrationNumber(OrganizationVO organization) {
        return Optional.ofNullable(organization.getOrganizationIdentification())
                .orElse(List.of())
                .stream()
                .map(OrganizationIdentificationVO::getIdentificationId)
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * Builds the legal address from the first contact medium that carries a country. The TM Forum
     * {@code country} is mapped onto the self-description's {@code countryCode}. An address is always
     * returned (empty if the organization has no country) so the receipt builder never sees a missing
     * legal address.
     */
    private AddressVO address(OrganizationVO organization) {
        return new AddressVO().countryCode(countryCode(organization).orElse(null));
    }

    private Optional<String> countryCode(OrganizationVO organization) {
        return Optional.ofNullable(organization.getContactMedium())
                .orElse(List.of())
                .stream()
                .map(ContactMediumVO::getCharacteristic)
                .filter(Objects::nonNull)
                .map(MediumCharacteristicVO::getCountry)
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * The ids of the organization's child organizations. Always non-null (empty when there are none).
     */
    private List<String> subOrganizations(OrganizationVO organization) {
        return Optional.ofNullable(organization.getOrganizationChildRelationship())
                .orElse(List.of())
                .stream()
                .map(OrganizationChildRelationshipVO::getOrganization)
                .filter(Objects::nonNull)
                .map(OrganizationRefVO::getId)
                .filter(Objects::nonNull)
                .toList();
    }
}
