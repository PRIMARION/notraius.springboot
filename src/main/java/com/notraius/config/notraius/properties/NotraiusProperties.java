package com.notraius.config.notraius.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.math.BigDecimal;

/**
 * Notraius microservice runtime configuration.
 * <p>
 * Identity lives in the primarion microservice, money movement in pay —
 * notraius resolves all of them over HTTP through the configured base
 * URLs and never holds foreign tables. The domain grows here as it
 * materializes; only {@code notificationsEnabled} exists today (the
 * shared communication module reads it).
 */
@ConfigurationProperties(prefix = "app.notraius")
@Getter
@Setter
public class NotraiusProperties {

    /**
     * Whether in-app notifications are written to {@code notraius.notifications}.
     */
    private boolean notificationsEnabled = true;

    /**
     * Platform code this microservice's reference data is scoped to.
     */
    private String platformCode = "NOTRAIUS";

    /**
     * Base URL of the pay microservice, used for service-to-service currency,
     * wallet and payment operations.
     */
    private String payBaseUrl;

    /**
     * Base URL of the logistic microservice, used for service-to-service
     * course/shipment reference operations as the notraius domain grows.
     */
    private String logisticBaseUrl;

    /**
     * How long a paid visit request may wait for the owner's side before it is
     * expired and refunded in full (PLAN.md 5.1's first fence). An
     * {@code .env}-bound number, never a literal: the rule is fixed (silence
     * never keeps money), the number rides the environment.
     */
    private int visitRequestExpiryHours = 24;

    /**
     * How far back the visit sweep looks for rows to expire, in hours. Kept
     * separate from the fence so a long outage cannot make the sweep walk the
     * whole table in one transaction.
     */
    private int visitSweepLookbackHours = 720;

    /**
     * How long the last editor may refine their own proposal in PLANNING before
     * the move belongs to the counterpart (PLAN.md 5.2's 30 minutes). Inside the
     * window a re-edit is legal and rolls the window forward from the new edit.
     */
    private int visitReeditWindowMinutes = 30;

    /**
     * The last fence (PLAN.md 5.2): any visit still unconfirmed this many hours
     * after creation expires and its money returns in full. The birth clock is
     * spent on first CONFIRMED — a later reschedule never re-arms it.
     */
    private int visitConfirmationFenceHours = 72;

    /**
     * The counter-signature window (PLAN.md 5.3): how long the other party has to
     * confirm a STARTED claim before the claim lapses and the no-show laws fire.
     */
    private int visitStartConfirmWindowMinutes = 10;

    /**
     * The assessment door's grace (PLAN.md 5.3): assessments are accepted from
     * the start mark until this many minutes past the visit's planned end. When
     * that instant passes the pair is sealed and the hour completes the visit.
     */
    private int visitAssessmentWindowMinutes = 60;

    /**
     * PLAN.md 5.4's horizon: a confirmed visit may be rescheduled or cancelled
     * only while it stands at least this many hours in the future. Exactly on the
     * line is still inside; the door shuts the first second after it.
     */
    private int visitCancelHorizonHours = 24;

    /**
     * The lease's birth clock (PLAN.md 6.1): a PENDING lease that nobody
     * confirmed inside this many hours expires, its held matrix refunded split by
     * split, and the property returns to the market. Silence never keeps money —
     * nor a listing.
     */
    private int leaseStalenessWindowHours = 72;

    /**
     * How far back the lease sweep looks, in hours. Kept separate from the
     * window so a long outage cannot make one tick walk the whole table.
     */
    private int leaseSweepLookbackHours = 720;

    /**
     * How many periods ahead an open-ended lease's calendar generates (6.2's
     * rolling horizon). Each later extension is a logged act; the number rides
     * the environment, the rolling rule does not.
     */
    private int leaseScheduleHorizonPeriods = 6;

    /** The notice engine's response window: silence past this escalates (6.5). */
    private int leaseNoticeResponseHours = 72;

    /** The VACATION notice's three months to vacate (6.5). */
    private int leaseVacationMonths = 3;

    /** The landlord's week to report the move-out conditions (6.7). */
    private int leaseMoveOutWindowDays = 7;

    /** The billed sides' fortnight at settlement (6.7). */
    private int leaseSettlementWindowDays = 14;

    /** The RECONVERSION pace: months per unpaid installment times this (6.6). */
    private java.math.BigDecimal leaseReconversionMultiplier = java.math.BigDecimal.valueOf(2.5);

    /**
     * The DEPOSIT grace: the frozen standard's span times this — time bought,
     * never immunity granted (6.5).
     */
    private int leaseDepositGraceMultiplier = 2;

    /**
     * How many work keys one sweep page carries. A page is ids and clock
     * values, not entities: five hundred pairs are two columns the database
     * streams, while five hundred thousand hydrated visits are an
     * out-of-memory. The number rides the environment, the paging law does
     * not.
     */
    private int sweepPageSize = 500;

    /**
     * How many rows one sweep pass may settle before it yields to the next
     * tick. A worklist larger than the budget is not a failure — the ledger
     * cursor resumes exactly after the last settled row, so the pass that
     * follows continues instead of restarting, and the row at the seam is
     * settled once, never twice.
     */
    private int sweepRowBudget = 5000;

    /** Platform target identifiers — never configurable: static constants. */
    private static final String PLATFORM_TARGET_PRIMARION = "PRIMARION";
    private static final String PLATFORM_TARGET_PAY = "PAY";
    /** Public because the location client names its target without injecting the bean. */
    public static final String PLATFORM_TARGET_LOGISTIC = "LOGISTIC";

    public String getPlatformTargetPrimarion() { return PLATFORM_TARGET_PRIMARION; }
    public String getPlatformTargetPay() { return PLATFORM_TARGET_PAY; }
    public String getPlatformTargetLogistic() { return PLATFORM_TARGET_LOGISTIC; }
}
