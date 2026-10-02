package com.obd.api.devicetoken;

import com.obd.api.car.Car;
import com.obd.api.car.CarAccess;
import com.obd.api.car.CarRepository;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.devicetoken.dto.DeviceTokenDTO;
import com.obd.api.devicetoken.exception.DeviceTokenNotFoundException;
import com.obd.api.group.*;
import com.obd.api.model.ModelRepository;
import com.obd.api.support.RepositoryTest;
import com.obd.api.user.Role;
import com.obd.api.user.User;
import com.obd.api.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Minting and withdrawing the credential the phone's background service uses,
 * against a real database.
 */
@RepositoryTest
@Import({DeviceTokenService.class, CarAccess.class})
class DeviceTokenServiceTest {

    private static final UUID SEEDED_GOL = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired
    private DeviceTokenService deviceTokenService;
    @Autowired
    private DeviceTokenRepository deviceTokenRepository;
    @Autowired
    private CarRepository carRepository;
    @Autowired
    private ModelRepository modelRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;
    @Autowired
    private GroupRepository groupRepository;
    @Autowired
    private GroupMemberRepository groupMemberRepository;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    private UUID adaId;      // owns the car
    private UUID graceId;    // shares it through the group
    private UUID strangerId; // no access
    private Car adasCar;

    private UUID newUser(String email) {
        return userRepository.saveAndFlush(User.builder()
                .userName("Test").userLastName("User").userEmail(email)
                .userPasswordHash("$2a$12$notarealhash")
                .role(Role.USER).enabled(true).build()).getUserId();
    }

    @BeforeEach
    void adaOwnsACarAndSharesIt() {
        adaId = newUser("ada@example.com");
        graceId = newUser("grace@example.com");
        strangerId = newUser("stranger@example.com");

        Group family = groupRepository.saveAndFlush(Group.builder().groupName("Familia").build());
        for (UUID member : new UUID[]{adaId, graceId}) {
            groupMemberRepository.saveAndFlush(GroupMember.of(family.getGroupId(), member, GroupRole.MEMBER));
        }
        adasCar = carRepository.saveAndFlush(Car.builder()
                .carOwnerId(adaId)
                .carModel(modelRepository.findById(SEEDED_GOL).orElseThrow())
                .carName("Ada's Gol")
                .carGroup(family)
                .build());
    }

    private static DeviceTokenDTO.Create named(String label) {
        return new DeviceTokenDTO.Create(label);
    }

    // --- mint ----------------------------------------------------------------

    @Test
    void mintingHandsBackTheTokenOnceAndStoresOnlyItsHash() {
        DeviceTokenDTO.Minted minted = deviceTokenService.mint(adaId, adasCar.getCarId(), named("Pixel de Ada"));

        assertThat(minted.token()).startsWith(DeviceTokenService.PREFIX);
        assertThat(minted.carId()).isEqualTo(adasCar.getCarId());
        assertThat(minted.label()).isEqualTo("Pixel de Ada");
        // 90 days of grace from minting, before it has ever been used.
        assertThat(minted.idleExpiresAt()).isAfter(Instant.now().plusSeconds(89 * 86400));

        DeviceToken stored = deviceTokenRepository.findAll().getFirst();
        assertThat(stored.getDeviceTokenHash()).isNotEqualTo(minted.token()).hasSize(64);
        assertThat(stored.getDeviceTokenUserId()).isEqualTo(adaId);
        assertThat(stored.getDeviceTokenLastUsedAt()).isNull();
        assertThat(stored.getDeviceTokenRevokedAt()).isNull();
    }

    @Test
    void twoMintsGiveTwoDifferentTokens() {
        // One per phone, each revocable on its own.
        String first = deviceTokenService.mint(adaId, adasCar.getCarId(), named("Pixel")).token();
        String second = deviceTokenService.mint(adaId, adasCar.getCarId(), named("Tablet")).token();

        assertThat(first).isNotEqualTo(second);
        assertThat(deviceTokenRepository.count()).isEqualTo(2);
    }

    @Test
    void aMemberOfTheGroupMayMintOneToo() {
        // Not owner-only on purpose: a member who may start trips by hand may
        // as well have them recorded automatically. The token can do no more
        // than its owner already could.
        assertThat(deviceTokenService.mint(graceId, adasCar.getCarId(), named("Pixel de Grace")).token())
                .isNotBlank();
    }

    @Test
    void someoneWithNoAccessToTheCarMintsNothing() {
        assertThatThrownBy(() -> deviceTokenService.mint(strangerId, adasCar.getCarId(), named("Ajeno")))
                .isInstanceOf(CarNotFoundException.class);
        assertThatThrownBy(() -> deviceTokenService.mint(adaId, UUID.randomUUID(), named("Fantasma")))
                .isInstanceOf(CarNotFoundException.class);

        assertThat(deviceTokenRepository.count()).isZero();
    }

    @Test
    void theLabelIsTrimmed() {
        assertThat(deviceTokenService.mint(adaId, adasCar.getCarId(), named("  Pixel de Ada  ")).label())
                .isEqualTo("Pixel de Ada");
    }

    // --- list ----------------------------------------------------------------

    @Test
    void theListShowsTheCallersOwnTokensAndNeverTheTokens() {
        deviceTokenService.mint(adaId, adasCar.getCarId(), named("Pixel"));
        deviceTokenService.mint(adaId, adasCar.getCarId(), named("Tablet"));
        deviceTokenService.mint(graceId, adasCar.getCarId(), named("Pixel de Grace"));

        var adas = deviceTokenService.forCar(adaId, adasCar.getCarId());

        // Only her own: another member's phone is that member's business, and
        // listing it would leak which family members have the app installed.
        assertThat(adas).hasSize(2)
                .extracting(DeviceTokenDTO.Read::label)
                .containsExactlyInAnyOrder("Pixel", "Tablet");
        assertThat(deviceTokenService.forCar(graceId, adasCar.getCarId())).hasSize(1);
    }

    @Test
    void revokedTokensDropOutOfTheList() {
        var minted = deviceTokenService.mint(adaId, adasCar.getCarId(), named("Pixel"));

        deviceTokenService.revoke(adaId, minted.id());

        assertThat(deviceTokenService.forCar(adaId, adasCar.getCarId())).isEmpty();
        // The row survives: it is the record that a phone had access.
        assertThat(deviceTokenRepository.count()).isEqualTo(1);
    }

    @Test
    void listingACarYouCannotSeeIsNotFound() {
        assertThatThrownBy(() -> deviceTokenService.forCar(strangerId, adasCar.getCarId()))
                .isInstanceOf(CarNotFoundException.class);
    }

    // --- revoke --------------------------------------------------------------

    @Test
    void revokingStampsTheRow() {
        var minted = deviceTokenService.mint(adaId, adasCar.getCarId(), named("Pixel"));

        deviceTokenService.revoke(adaId, minted.id());

        assertThat(deviceTokenRepository.findById(minted.id()).orElseThrow()
                .getDeviceTokenRevokedAt()).isNotNull();
    }

    @Test
    void revokingTwiceOrSomeoneElsesIsTheSameAnswerAsUnknown() {
        var minted = deviceTokenService.mint(adaId, adasCar.getCarId(), named("Pixel"));
        deviceTokenService.revoke(adaId, minted.id());

        // One answer for already-revoked, somebody else's, and never existed -
        // so a token id is never confirmed to a stranger.
        assertThatThrownBy(() -> deviceTokenService.revoke(adaId, minted.id()))
                .isInstanceOf(DeviceTokenNotFoundException.class);
        assertThatThrownBy(() -> deviceTokenService.revoke(graceId, minted.id()))
                .isInstanceOf(DeviceTokenNotFoundException.class);
        assertThatThrownBy(() -> deviceTokenService.revoke(adaId, UUID.randomUUID()))
                .isInstanceOf(DeviceTokenNotFoundException.class);
    }

    @Test
    void oneMembersTokenCannotBeRevokedByAnother() {
        var graces = deviceTokenService.mint(graceId, adasCar.getCarId(), named("Pixel de Grace"));

        // Even though Ada owns the car.
        assertThatThrownBy(() -> deviceTokenService.revoke(adaId, graces.id()))
                .isInstanceOf(DeviceTokenNotFoundException.class);
        assertThat(deviceTokenRepository.findById(graces.id()).orElseThrow()
                .getDeviceTokenRevokedAt()).isNull();
    }

    // --- the idle deadline ---------------------------------------------------

    @Test
    void theIdleDeadlineFollowsTheLastUseAndThenTheToken_dies() {
        var minted = deviceTokenService.mint(adaId, adasCar.getCarId(), named("Pixel"));
        DeviceToken token = deviceTokenRepository.findById(minted.id()).orElseThrow();

        assertThat(token.isLiveAt(Instant.now())).isTrue();
        // 90 days of silence and it stops working - the safety net for a phone
        // that was lost or wiped without anybody revoking anything.
        assertThat(token.isLiveAt(Instant.now().plusSeconds(91 * 86400))).isFalse();

        // Using it pushes the deadline out.
        Instant usedAt = Instant.now().plusSeconds(80 * 86400);
        token.setDeviceTokenLastUsedAt(usedAt);
        assertThat(token.isLiveAt(Instant.now().plusSeconds(91 * 86400))).isTrue();
        assertThat(token.idleDeadline()).isEqualTo(usedAt.plusSeconds(90 * 86400));
    }

    @Test
    void deletingTheCarOrTheAccountTakesTheTokensWithIt() {
        deviceTokenService.mint(adaId, adasCar.getCarId(), named("Pixel"));
        // The cascade happens in the database, so the persistence context has
        // to be out of the way for the count below to see it.
        entityManager.clear();

        carRepository.deleteById(adasCar.getCarId());
        entityManager.flush();
        entityManager.clear();

        // on delete cascade in V15: a credential for a car that is gone is nothing.
        assertThat(deviceTokenRepository.count()).isZero();
    }
}
