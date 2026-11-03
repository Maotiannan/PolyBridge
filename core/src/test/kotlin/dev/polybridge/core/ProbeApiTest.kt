package dev.polybridge.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ProbeApiTest {
    @Test fun selectingAnEarphoneCannotPassOnThePhoneMicrophone() {
        val expectedPort = 14
        assertEquals(Verdict.ROUTE_MISMATCH, qualifyRoute(Verdict.SIGNAL, expectedPort, Route(15, 7)))
        assertEquals(Verdict.ROUTE_UNKNOWN, qualifyRoute(Verdict.SIGNAL, expectedPort, null))
        assertEquals(Verdict.SIGNAL, qualifyRoute(Verdict.SIGNAL, expectedPort, Route(7, expectedPort)))
        assertEquals(Verdict.CANCELLED, qualifyRoute(Verdict.CANCELLED, expectedPort, null))
    }

    @Test fun reportsNormalizedEnergyAcrossChunksWithoutReadingBufferTail() {
        val meter = SignalMeter()
        meter.add(shortArrayOf(16384, -16384, 32767), 2)
        meter.add(shortArrayOf(0, 0), 2)
        val stats = meter.snapshot()
        assertEquals(4L, stats.samples)
        assertEquals(2L, stats.nonZeroSamples)
        assertEquals(-6.0206, stats.peakDbfs!!, 0.001)
        assertEquals(-9.0309, stats.rmsDbfs!!, 0.001)
        assertEquals(Verdict.SIGNAL, assess(stats, 4, null, End.TIMED))
    }

    @Test fun noDataSilenceAndSystemMutingAreDifferentOutcomes() {
        val meter = SignalMeter()
        assertEquals(Verdict.NO_DATA, assess(meter.snapshot(), 10, null, End.TIMED))
        meter.add(ShortArray(10), 10)
        assertNull(meter.snapshot().rmsDbfs)
        assertEquals(Verdict.SILENT, assess(meter.snapshot(), 10, false, End.TIMED))
        assertEquals(Verdict.SYSTEM_SILENCED, assess(meter.snapshot(), 10, true, End.TIMED))
    }

    @Test fun partialCaptureDeviceLossRevocationAndCancellationCannotPass() {
        val meter = SignalMeter()
        meter.add(shortArrayOf(Short.MIN_VALUE), 1)
        assertEquals(0.0, meter.snapshot().peakDbfs!!, 0.00001)
        val stats = meter.snapshot()
        assertEquals(Verdict.INCOMPLETE, assess(stats, 100, false, End.TIMED))
        assertEquals(Verdict.ROUTE_LOST, assess(stats, 1, false, End.ROUTE_LOST))
        assertEquals(Verdict.REVOKED, assess(stats, 1, false, End.PROJECTION_STOPPED))
        assertEquals(Verdict.CANCELLED, assess(stats, 1, false, End.CANCELLED))
        assertEquals(Verdict.DENIED, assess(stats, 1, false, End.DENIED))
    }

    @Test fun reportRoundTripsTypedEvidenceWithoutRawAudioOrHardwareAddresses() {
        val entry = ProbeEntry(id = "test", kind = Kind.MICROPHONE, target = PlaybackTarget.SELF,
            startedAtMs = 123, verdict = Verdict.NO_DATA, requestedInputId = 24)
        val report = ProbeReport(device = DeviceFacts("Fold", "6", "16", 36, "2026-07-01",
            fingerprintHash("build fingerprint"), "test"), probes = listOf(entry))
        val json = ReportCodec.json(report)
        val parsed = JsonParser.parseString(json).asJsonObject
        assertEquals(1, parsed["schemaVersion"].asInt)
        assertFalse(json.contains("build fingerprint"))
        assertFalse(json.contains("address", ignoreCase = true))
        assertFalse(json.contains("pcm", ignoreCase = true))
        assertEquals(report, ReportCodec.read(json))
        assertTrue(ReportCodec.markdown(report).contains("NO_DATA"))
        assertNull(entry.inputRoute)
        assertEquals(Capability.UNVERIFIED, report.independentBluetoothMics)
        assertEquals(Capability.UNVERIFIED, report.simultaneousBluetoothOutput)
    }
}
