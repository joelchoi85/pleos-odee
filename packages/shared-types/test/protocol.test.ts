import { describe, expect, it } from 'vitest';
import {
  deriveDrivingSafetyLevel,
  VehicleTelemetryPayloadSchema,
} from '../src/index.js';

describe('@odee/shared-types protocol', () => {
  it('derives DrivingSafetyLevel accurately from speed, gear, and brake', () => {
    expect(
      deriveDrivingSafetyLevel({ speedKmh: 0, gear: 'P', isBraking: false }),
    ).toBe('STATIONARY');

    expect(
      deriveDrivingSafetyLevel({ speedKmh: 35, gear: 'D', isBraking: false }),
    ).toBe('NORMAL_DRIVING');

    expect(
      deriveDrivingSafetyLevel({ speedKmh: 85, gear: 'D', isBraking: false }),
    ).toBe('HIGH_FOCUS');

    expect(
      deriveDrivingSafetyLevel({ speedKmh: 25, gear: 'D', isBraking: true }),
    ).toBe('HIGH_FOCUS');
  });

  it('validates a Pleos EV telemetry payload with SOC and brake sensors', () => {
    const sample = {
      groupId: 'GRP-20261102-99F',
      vehicleId: 'HYUNDAI-IONIQ6-02',
      displayName: '2호차 (아이오닉 6)',
      role: 'PLEOS_COMPANION',
      timestampMs: Date.now(),
      location: {
        lat: 37.3948,
        lng: 127.1112,
        heading: 180.5,
        speedKmh: 82.0,
      },
      vehicleState: {
        powertrain: 'EV',
        socPercent: 16,
        remainingRangeKm: 58,
        gear: 'D',
        isBraking: false,
      },
      routeState: {
        activeRouteId: 'RT-ORIGINAL-001',
        etaIso: '2026-11-02T19:45:00Z',
      },
    };

    const parsed = VehicleTelemetryPayloadSchema.safeParse(sample);
    expect(parsed.success).toBe(true);
  });
});
