import type {
  DeviationCategory,
  VehicleTelemetryPayload,
} from '@odee/shared-types';
import type { NearbyPoiItem } from '../providers/map-routing.interface.js';

export interface DeviationAiInput {
  telemetry: VehicleTelemetryPayload;
  crossTrackDistanceMeters: number;
  nearbyPois: NearbyPoiItem[];
  etaDeltaMinutes: number;
}

export interface DeviationAiOutput {
  category: DeviationCategory;
  headlineKo: string;
  detailKo: string;
  ttsScriptKo: string;
  matchedPoi?: NearbyPoiItem;
}

export type LlmFetcher = (
  input: DeviationAiInput,
  fallbackCategory: DeviationCategory,
  signal: AbortSignal,
) => Promise<Partial<DeviationAiOutput> | null>;

export class DeviationAiService {
  public static readonly OFF_ROUTE_THRESHOLD_METERS = 120;
  public static readonly REQUIRED_CONSECUTIVE_TICKS = 2;
  public static readonly LOW_SOC_THRESHOLD_PERCENT = 20;
  public static readonly LOW_FUEL_THRESHOLD_PERCENT = 15;
  public static readonly MIN_SIGNIFICANT_SAVINGS_MINUTES = 3;

  private readonly timeoutMs: number;
  private readonly customLlmFetcher?: LlmFetcher;

  constructor(options?: { timeoutMs?: number; customLlmFetcher?: LlmFetcher }) {
    this.timeoutMs =
      options?.timeoutMs ?? Number(process.env.AI_TIMEOUT_MS || 1500);
    this.customLlmFetcher = options?.customLlmFetcher;
  }

  /**
   * 1단계(규칙+차량 센서 데이터 판별) + 2단계(LLM 자연어 맥락 브리핑 생성 및 타임아웃 폴백)
   */
  async analyzeDeviation(input: DeviationAiInput): Promise<DeviationAiOutput> {
    const ruleResult = this.classifyByVehicleSensorsAndPoi(input);

    // LLM 브리핑 보강 시도 (네트워크 지연 시 timeoutMs 내 즉시 규칙 결과로 폴백)
    const llmUpdater = this.customLlmFetcher ?? this.defaultLlmFetcher.bind(this);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);

    try {
      const enhanced = await llmUpdater(
        input,
        ruleResult.category,
        controller.signal,
      );
      if (
        enhanced &&
        enhanced.headlineKo &&
        enhanced.ttsScriptKo
      ) {
        return {
          ...ruleResult,
          category: enhanced.category ?? ruleResult.category,
          headlineKo: enhanced.headlineKo,
          detailKo: enhanced.detailKo ?? ruleResult.detailKo,
          ttsScriptKo: enhanced.ttsScriptKo,
        };
      }
    } catch {
      // 타임아웃 또는 API 오류 시 안전을 위해 지체 없이 1단계 결정론적 브리핑 반환
    } finally {
      clearTimeout(timer);
    }

    return ruleResult;
  }

  /**
   * Pleos Vehicle SDK (EvBattery / Fuel / Speed) 데이터와 주변 POI를 결합한 결정론적 맥락 분류
   */
  classifyByVehicleSensorsAndPoi(input: DeviationAiInput): DeviationAiOutput {
    const { telemetry, nearbyPois, etaDeltaMinutes } = input;
    const { vehicleState, displayName, location } = telemetry;

    const isLowEv =
      vehicleState.powertrain === 'EV' &&
      typeof vehicleState.socPercent === 'number' &&
      vehicleState.socPercent <= DeviationAiService.LOW_SOC_THRESHOLD_PERCENT;

    const isLowFuel =
      vehicleState.powertrain !== 'EV' &&
      typeof vehicleState.fuelPercent === 'number' &&
      vehicleState.fuelPercent <= DeviationAiService.LOW_FUEL_THRESHOLD_PERCENT;

    const chargerOrGasPoi = nearbyPois.find((poi) =>
      vehicleState.powertrain === 'EV'
        ? poi.category === 'EV_CHARGER' && poi.distanceMeters <= 600
        : poi.category === 'GAS_STATION' && poi.distanceMeters <= 600,
    );

    if ((isLowEv || isLowFuel) && chargerOrGasPoi) {
      const energyLabel = isLowEv
        ? `배터리 ${vehicleState.socPercent}%`
        : `연료 ${vehicleState.fuelPercent}%`;
      const actionWord = isLowEv ? '충전' : '주유';

      return {
        category: 'INTENTIONAL_PITSTOP_CHARGE_FUEL',
        matchedPoi: chargerOrGasPoi,
        headlineKo: `${displayName} ${actionWord} 목적 진입 (${energyLabel})`,
        detailKo: `${chargerOrGasPoi.name} 인근 진입이 감지되었습니다. 함께 경유(+${Math.abs(etaDeltaMinutes)}분)하거나 보이스를 연결할까요?`,
        ttsScriptKo: `${displayName}가 ${energyLabel} 상태로 ${chargerOrGasPoi.name}에 진입했습니다. 함께 경유하거나 그룹 보이스를 연결할까요?`,
      };
    }

    const restPoi = nearbyPois.find(
      (poi) =>
        (poi.category === 'REST_AREA' || poi.category === 'PARKING') &&
        poi.distanceMeters <= 450,
    );
    if (restPoi && location.speedKmh < 45) {
      return {
        category: 'INTENTIONAL_PITSTOP_REST',
        matchedPoi: restPoi,
        headlineKo: `${displayName} 휴식 경유 진입`,
        detailKo: `${restPoi.name} 정차 패턴입니다. 보이스로 휴식 여부를 확인할까요?`,
        ttsScriptKo: `${displayName}가 ${restPoi.name}로 진입했습니다. 그룹 보이스를 연결해 상황을 확인할까요?`,
      };
    }

    return {
      category: 'ACCIDENTAL_MISSED_EXIT',
      headlineKo: `${displayName} 경로 이탈 (+${Math.max(1, Math.abs(etaDeltaMinutes))}분)`,
      detailKo: `분기점 오진입으로 추정됩니다(속도 ${Math.round(location.speedKmh)}km/h). 보이스 연결 또는 경로를 유지하세요.`,
      ttsScriptKo: `${displayName}가 분기점을 벗어나 경로를 이탈했습니다. 재합류 시 약 ${Math.max(1, Math.abs(etaDeltaMinutes))}분 지연이 예상됩니다. 그룹 보이스를 연결할까요?`,
    };
  }

  private async defaultLlmFetcher(
    input: DeviationAiInput,
    fallbackCategory: DeviationCategory,
    signal: AbortSignal,
  ): Promise<Partial<DeviationAiOutput> | null> {
    const geminiKey = process.env.GEMINI_API_KEY;
    if (!geminiKey || process.env.AI_PROVIDER === 'RULE_ONLY') {
      return null;
    }

    const prompt = JSON.stringify({
      role: 'Pleos Connect In-Car Group Driving Safety AI',
      vehicle: input.telemetry.displayName,
      speedKmh: input.telemetry.location.speedKmh,
      socPercent: input.telemetry.vehicleState.socPercent,
      fuelPercent: input.telemetry.vehicleState.fuelPercent,
      crossTrackMeters: input.crossTrackDistanceMeters,
      nearbyPois: input.nearbyPois.slice(0, 3),
      suggestedCategory: fallbackCategory,
      etaDeltaMinutes: input.etaDeltaMinutes,
      instruction:
        'Return JSON { "headlineKo": "<=24 chars", "detailKo": "<=70 chars", "ttsScriptKo": "natural Korean 1-2 sentences ending with question" }',
    });

    const res = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${geminiKey}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          contents: [{ parts: [{ text: prompt }] }],
          generationConfig: { responseMimeType: 'application/json' },
        }),
        signal,
      },
    );

    if (!res.ok) return null;
    const data = (await res.json()) as {
      candidates?: Array<{
        content?: { parts?: Array<{ text?: string }> };
      }>;
    };
    const rawText = data.candidates?.[0]?.content?.parts?.[0]?.text;
    if (!rawText) return null;
    return JSON.parse(rawText) as Partial<DeviationAiOutput>;
  }
}
