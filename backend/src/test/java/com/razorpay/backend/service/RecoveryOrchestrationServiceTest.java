package com.razorpay.backend.service;

import com.razorpay.RazorpayException;
import com.razorpay.backend.dto.DashboardStatsDto;
import com.razorpay.backend.dto.PaymentFailureEventDto;
import com.razorpay.backend.entity.RecoveryAudit;
import com.razorpay.backend.gateway.PaymentLinkGateway;
import com.razorpay.backend.repository.RecoveryAuditRepository;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RecoveryOrchestrationService's local policy fallback:
 *
 *   ABORT          - attempts_so_far >= 3, or error in {CARD_BLOCKED, STOLEN_CARD}
 *   AUTO_RETRY     - GATEWAY_TIMEOUT
 *   VOICE_OUTREACH - user-side friction, amount >= ₹1,500
 *   WHATSAPP_LINK  - everything else
 *
 * The AI engine is pointed at an unreachable URL with a request factory
 * that always throws, so every test exercises localPolicyDecision()
 * deterministically and offline (no real network calls to the Python
 * engine). Razorpay payment link creation is mocked via the plain
 * PaymentLinkGateway interface rather than the SDK's RazorpayClient
 * class directly, since mocking third-party SDK types with Mockito's
 * inline bytecode instrumentation can fail depending on JVM version.
 */
@ExtendWith(MockitoExtension.class)
class RecoveryOrchestrationServiceTest {

    @Mock
    private RecoveryAuditRepository auditRepository;

    @Mock
    private PaymentLinkGateway paymentLinkGateway;

    private RecoveryOrchestrationService service;

    @BeforeEach
    void setUp() {
        // Request factory that always fails fast -> AI engine calls always
        // fall through to localPolicyDecision(), with no real HTTP call.
        ClientHttpRequestFactory failingFactory = (uri, httpMethod) -> {
            throw new java.io.IOException("AI engine intentionally unreachable in tests");
        };
        RestClient.Builder builder = RestClient.builder().requestFactory(failingFactory);

        service = new RecoveryOrchestrationService(
                auditRepository, paymentLinkGateway, builder, "http://localhost:9999/unreachable");

        when(auditRepository.save(any(RecoveryAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void processFailure_cardBlocked_isAbortedWithZeroCost() {
        PaymentFailureEventDto event = new PaymentFailureEventDto(
                "pay_hardblock01", BigDecimal.valueOf(2500), "CARD_BLOCKED",
                "Test Customer", "+919999999999", 0);

        RecoveryAudit result = service.processFailure(event);

        assertThat(result.getStatus()).isEqualTo("ABORTED");
        assertThat(result.getActionTaken()).isEqualTo("ABORT");
        assertThat(result.getPaymentLinkUrl()).isNull();
    }

    @Test
    void processFailure_stolenCard_isAborted() {
        PaymentFailureEventDto event = new PaymentFailureEventDto(
                "pay_stolen01", BigDecimal.valueOf(3200), "STOLEN_CARD",
                "Test Customer", "+919999999999", 0);

        RecoveryAudit result = service.processFailure(event);

        assertThat(result.getStatus()).isEqualTo("ABORTED");
        assertThat(result.getActionTaken()).isEqualTo("ABORT");
    }

    @Test
    void processFailure_maxAttemptsExceeded_isAborted() {
        PaymentFailureEventDto event = new PaymentFailureEventDto(
                "pay_maxattempts01", BigDecimal.valueOf(500), "GATEWAY_TIMEOUT",
                "Test Customer", "+919999999999", 3);

        RecoveryAudit result = service.processFailure(event);

        assertThat(result.getStatus()).isEqualTo("ABORTED");
        assertThat(result.getActionTaken()).isEqualTo("ABORT");
    }

    @Test
    void processFailure_gatewayTimeout_autoRetriesWithoutPaymentLink() {
        PaymentFailureEventDto event = new PaymentFailureEventDto(
                "pay_gwtimeout01", BigDecimal.valueOf(800), "GATEWAY_TIMEOUT",
                "Test Customer", "+919999999999", 0);

        RecoveryAudit result = service.processFailure(event);

        assertThat(result.getStatus()).isEqualTo("RECOVERED");
        assertThat(result.getActionTaken()).isEqualTo("AUTO_RETRY");
        assertThat(result.getPaymentLinkUrl()).isNull();
        assertThat(result.getInterventionCost()).isEqualByComparingTo(BigDecimal.valueOf(0.05));
    }

    @Test
    void processFailure_highValueFriction_usesVoiceOutreach() {
        PaymentFailureEventDto event = new PaymentFailureEventDto(
                "pay_highvalue123456", BigDecimal.valueOf(5000), "INSUFFICIENT_FUNDS",
                "Priya Sharma", "+919876543210", 0);

        RecoveryAudit result = service.processFailure(event);

        assertThat(result.getActionTaken()).isEqualTo("VOICE_OUTREACH");
        assertThat(result.getInterventionCost()).isEqualByComparingTo(BigDecimal.valueOf(1.20));
    }

    @Test
    void processFailure_lowValueFriction_usesWhatsappLink() {
        PaymentFailureEventDto event = new PaymentFailureEventDto(
                "pay_lowvalue01", BigDecimal.valueOf(600), "INSUFFICIENT_FUNDS",
                "Rohan Iyer", "+919812345678", 0);

        RecoveryAudit result = service.processFailure(event);

        assertThat(result.getActionTaken()).isEqualTo("WHATSAPP_LINK");
        assertThat(result.getInterventionCost()).isEqualByComparingTo(BigDecimal.valueOf(0.35));
    }

    @Test
    void processFailure_liveLinkAction_whenRazorpayLinkFails_fallsBackToMockLinkAndRecovers()
            throws RazorpayException {
        when(paymentLinkGateway.createPaymentLink(any(JSONObject.class)))
                .thenThrow(new RazorpayException("Invalid test/placeholder API key"));

        PaymentFailureEventDto event = new PaymentFailureEventDto(
                "pay_mockfallback123", BigDecimal.valueOf(5000), "INSUFFICIENT_FUNDS",
                "Priya Sharma", "+919876543210", 0);

        RecoveryAudit result = service.processFailure(event);

        assertThat(result.getStatus()).isEqualTo("RECOVERED");
        assertThat(result.getPaymentLinkUrl()).isEqualTo("https://rzp.io/i/recover-lback123");
        assertThat(result.getRecoveredAmount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
    }

    @Test
    void processFailure_liveLinkAction_whenRazorpayLinkSucceeds_usesLiveLink() throws RazorpayException {
        when(paymentLinkGateway.createPaymentLink(any(JSONObject.class)))
                .thenReturn("https://rzp.io/i/LIVE123");

        PaymentFailureEventDto event = new PaymentFailureEventDto(
                "pay_livelink01", BigDecimal.valueOf(3000), "INSUFFICIENT_FUNDS",
                "Rohan Iyer", "+919812345678", 0);

        RecoveryAudit result = service.processFailure(event);

        assertThat(result.getStatus()).isEqualTo("RECOVERED");
        assertThat(result.getPaymentLinkUrl()).isEqualTo("https://rzp.io/i/LIVE123");
    }

    @Test
    void processBatch_processesAllEventsConcurrentlyOnVirtualThreads() {
        List<PaymentFailureEventDto> events = List.of(
                new PaymentFailureEventDto("pay_batch01", BigDecimal.valueOf(200), "GATEWAY_TIMEOUT",
                        "A", "+911111111111", 0),
                new PaymentFailureEventDto("pay_batch02", BigDecimal.valueOf(300), "CARD_BLOCKED",
                        "B", "+912222222222", 0),
                new PaymentFailureEventDto("pay_batch03", BigDecimal.valueOf(400), "MANDATE_EXPIRED",
                        "C", "+913333333333", 4)
        );

        List<RecoveryAudit> results = service.processBatch(events);

        assertThat(results).hasSize(3);
        ArgumentCaptor<RecoveryAudit> captor = ArgumentCaptor.forClass(RecoveryAudit.class);
        org.mockito.Mockito.verify(auditRepository, org.mockito.Mockito.times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(RecoveryAudit::getTransactionId)
                .containsExactlyInAnyOrder("pay_batch01", "pay_batch02", "pay_batch03");
    }

    @Test
    void processBatch_emptyList_returnsEmptyResultWithoutTouchingRepository() {
        List<RecoveryAudit> results = service.processBatch(List.<PaymentFailureEventDto>of());

        assertThat(results).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(auditRepository);
    }

    @Test
    void processBatch_intCount_generatesAndProcessesSyntheticEvents() {
        List<RecoveryAudit> results = service.processBatch(25);

        assertThat(results).hasSize(25);
        org.mockito.Mockito.verify(auditRepository, org.mockito.Mockito.times(25))
                .save(any(RecoveryAudit.class));
    }

    @Test
    void getDashboardStats_computesRecoveryRateFromRepositoryAggregates() {
        when(auditRepository.getTotalAtRisk()).thenReturn(BigDecimal.valueOf(10000));
        when(auditRepository.getTotalRecovered()).thenReturn(BigDecimal.valueOf(6000));
        when(auditRepository.getTotalCost()).thenReturn(BigDecimal.valueOf(120));
        when(auditRepository.count()).thenReturn(10L);
        when(auditRepository.findByStatus("RECOVERED"))
                .thenReturn(List.of(new RecoveryAudit(), new RecoveryAudit(), new RecoveryAudit(),
                        new RecoveryAudit(), new RecoveryAudit(), new RecoveryAudit()));

        DashboardStatsDto stats = service.getDashboardStats();

        assertThat(stats.totalAtRisk()).isEqualByComparingTo(BigDecimal.valueOf(10000));
        assertThat(stats.totalRecovered()).isEqualByComparingTo(BigDecimal.valueOf(6000));
        assertThat(stats.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(120));
        assertThat(stats.totalTransactions()).isEqualTo(10L);
        assertThat(stats.recoveryRate()).isEqualTo(60.0);
    }
}