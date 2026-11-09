import type { GeoPoint, PoiCategory } from '@odee/shared-types';
import { haversineDistanceMeters } from '../utils/geo.js';
import type {
  MapRoutingProvider,
  NearbyPoiItem,
  StandardRouteResult,
} from './map-routing.interface.js';

/**
 * 판교 테크노밸리 -> 판교 IC -> 경부고속도로 -> 양재 IC -> 현대모터스튜디오 서울(강남) 기준 경로
 * (API 키 미설정 오프라인 시연 환경에서도 100% 실제 좌표 기반으로 작동)
 */
export const DEMO_BASELINE_POLYLINE: GeoPoint[] = [
  { lat: 37.402, lng: 127.1086 }, // 판교역/테크노밸리 출발
  { lat: 37.4055, lng: 127.1032 }, // 판교 IC 진입로
  { lat: 37.4162, lng: 127.0978 }, // 경부고속도로 달래내 구간
  { lat: 37.4345, lng: 127.0752 }, // 청계산 구간
  { lat: 37.4638, lng: 127.0418 }, // 양재 IC
  { lat: 37.4979, lng: 127.0276 }, // 강남역
  { lat: 37.5214, lng: 127.0342 }, // 현대모터스튜디오 서울 목적지
];

export const DEMO_SHORTCUT_POLYLINE: GeoPoint[] = [
  { lat: 37.402, lng: 127.1086 },
  { lat: 37.4125, lng: 127.1155 }, // 분당-내곡 도시고속화도로 우회 진입
  { lat: 37.448, lng: 127.089 }, // 내곡터널 우회 구간 (정체 회피 -8분)
  { lat: 37.4845, lng: 127.052 }, // 구룡터널/개포 우회
  { lat: 37.5214, lng: 127.0342 },
];

export const DEMO_CORRIDOR_POIS: Omit<NearbyPoiItem, 'distanceMeters'>[] = [
  {
    id: 'POI-EPIT-PANGYO',
    name: '판교 E-pit 초급속충전소',
    category: 'EV_CHARGER',
    location: { lat: 37.4038, lng: 127.0985 },
  },
  {
    id: 'POI-REST-MEETING',
    name: '서울만남의광장 휴게소 (급속충전/주유)',
    category: 'EV_CHARGER',
    location: { lat: 37.4602, lng: 127.0421 },
  },
  {
    id: 'POI-GAS-PANGYO',
    name: '판교IC 직영 주유소',
    category: 'GAS_STATION',
    location: { lat: 37.4045, lng: 127.0992 },
  },
  {
    id: 'POI-REST-DALRAE',
    name: '청계 졸음쉼터',
    category: 'REST_AREA',
    location: { lat: 37.435, lng: 127.076 },
  },
];

export class KakaoMobilityProvider implements MapRoutingProvider {
  public readonly vendorId = 'KAKAO' as const;
  private readonly apiKey: string | undefined;

  constructor(apiKey?: string) {
    this.apiKey = apiKey || process.env.KAKAO_REST_API_KEY || undefined;
  }

  async calculateRoute(
    origin: GeoPoint,
    destination: GeoPoint,
    waypoints: GeoPoint[] = [],
  ): Promise<StandardRouteResult> {
    if (this.apiKey) {
      try {
        const url = new URL(
          'https://apis-navi.kakaomobility.com/v1/directions',
        );
        url.searchParams.set('origin', `${origin.lng},${origin.lat}`);
        url.searchParams.set(
          'destination',
          `${destination.lng},${destination.lat}`,
        );
        if (waypoints.length > 0) {
          url.searchParams.set(
            'waypoints',
            waypoints.map((w) => `${w.lng},${w.lat}`).join('|'),
          );
        }

        const res = await fetch(url, {
          headers: { Authorization: `KakaoAK ${this.apiKey}` },
          signal: AbortSignal.timeout(2500),
        });

        if (res.ok) {
          const data = (await res.json()) as {
            routes?: Array<{
              summary?: { distance: number; duration: number };
              sections?: Array<{
                roads?: Array<{ vertexes?: number[] }>;
              }>;
            }>;
          };
          const first = data.routes?.[0];
          if (first?.summary) {
            const polyline: GeoPoint[] = [];
            for (const sec of first.sections ?? []) {
              for (const road of sec.roads ?? []) {
                const v = road.vertexes ?? [];
                for (let i = 0; i < v.length - 1; i += 2) {
                  polyline.push({ lng: v[i], lat: v[i + 1] });
                }
              }
            }
            return {
              provider: 'KAKAO',
              distanceMeters: first.summary.distance,
              durationSeconds: first.summary.duration,
              polyline:
                polyline.length >= 2 ? polyline : DEMO_BASELINE_POLYLINE,
            };
          }
        }
      } catch {
        // 네트워크 단절 또는 API 한도 초과 시 시연 안정성을 위해 내장 경로로 즉시 폴백
      }
    }

    return this.calculateFallbackRoute(origin, destination, waypoints);
  }

  async searchNearbyPois(
    center: GeoPoint,
    radiusMeters: number,
  ): Promise<NearbyPoiItem[]> {
    if (this.apiKey) {
      try {
        const categories: Array<{ code: string; mapped: PoiCategory }> = [
          { code: 'OL7', mapped: 'GAS_STATION' },
          { code: 'PK6', mapped: 'PARKING' },
        ];
        const results: NearbyPoiItem[] = [];

        // 전기차 충전소 키워드 검색 + 주유소 카테고리 검색 병합
        const evUrl = new URL(
          'https://dapi.kakao.com/v2/local/search/keyword.json',
        );
        evUrl.searchParams.set('query', '전기차충전소');
        evUrl.searchParams.set('x', String(center.lng));
        evUrl.searchParams.set('y', String(center.lat));
        evUrl.searchParams.set('radius', String(Math.min(radiusMeters, 5000)));
        evUrl.searchParams.set('size', '3');

        const evRes = await fetch(evUrl, {
          headers: { Authorization: `KakaoAK ${this.apiKey}` },
          signal: AbortSignal.timeout(1500),
        });
        if (evRes.ok) {
          const evData = (await evRes.json()) as {
            documents?: Array<{
              id: string;
              place_name: string;
              x: string;
              y: string;
              distance: string;
            }>;
          };
          for (const doc of evData.documents ?? []) {
            results.push({
              id: `KAKAO-${doc.id}`,
              name: doc.place_name,
              category: 'EV_CHARGER',
              location: { lat: Number(doc.y), lng: Number(doc.x) },
              distanceMeters: Number(doc.distance) || 0,
            });
          }
        }

        for (const cat of categories) {
          const catUrl = new URL(
            'https://dapi.kakao.com/v2/local/search/category.json',
          );
          catUrl.searchParams.set('category_group_code', cat.code);
          catUrl.searchParams.set('x', String(center.lng));
          catUrl.searchParams.set('y', String(center.lat));
          catUrl.searchParams.set(
            'radius',
            String(Math.min(radiusMeters, 5000)),
          );
          catUrl.searchParams.set('size', '2');

          const catRes = await fetch(catUrl, {
            headers: { Authorization: `KakaoAK ${this.apiKey}` },
            signal: AbortSignal.timeout(1500),
          });
          if (catRes.ok) {
            const catData = (await catRes.json()) as {
              documents?: Array<{
                id: string;
                place_name: string;
                x: string;
                y: string;
                distance: string;
              }>;
            };
            for (const doc of catData.documents ?? []) {
              results.push({
                id: `KAKAO-${doc.id}`,
                name: doc.place_name,
                category: cat.mapped,
                location: { lat: Number(doc.y), lng: Number(doc.x) },
                distanceMeters: Number(doc.distance) || 0,
              });
            }
          }
        }

        if (results.length > 0) {
          return results.sort((a, b) => a.distanceMeters - b.distanceMeters);
        }
      } catch {
        // 오프라인 폴백
      }
    }

    return DEMO_CORRIDOR_POIS.map((poi) => ({
      ...poi,
      distanceMeters: Math.round(
        haversineDistanceMeters(center, poi.location),
      ),
    }))
      .filter((poi) => poi.distanceMeters <= radiusMeters)
      .sort((a, b) => a.distanceMeters - b.distanceMeters);
  }

  private calculateFallbackRoute(
    origin: GeoPoint,
    destination: GeoPoint,
    waypoints: GeoPoint[],
  ): StandardRouteResult {
    // 분당-내곡 도시고속화도로 우회 지점 근처 경유 시 8분(480초) 단축 시뮬레이션
    const isNaegokShortcut = waypoints.some(
      (wp) =>
        haversineDistanceMeters(wp, { lat: 37.448, lng: 127.089 }) < 2500 ||
        haversineDistanceMeters(wp, { lat: 37.4125, lng: 127.1155 }) < 2500,
    );

    if (isNaegokShortcut) {
      return {
        provider: 'DEMO_CORRIDOR',
        distanceMeters: 15_400,
        durationSeconds: 1_320, // 22분 (기본 30분 대비 -8분 단축)
        polyline: DEMO_SHORTCUT_POLYLINE,
      };
    }

    if (waypoints.length > 0) {
      const detourMeters = haversineDistanceMeters(origin, waypoints[0]) +
        haversineDistanceMeters(waypoints[0], destination);
      return {
        provider: 'DEMO_CORRIDOR',
        distanceMeters: Math.round(Math.max(16_800, detourMeters)),
        durationSeconds: 1_800 + waypoints.length * 330, // 경유지당 약 +5.5분
        polyline: [origin, ...waypoints, destination],
      };
    }

    return {
      provider: 'DEMO_CORRIDOR',
      distanceMeters: 16_800,
      durationSeconds: 1_800, // 기본 30분 소요 (판교 -> 강남 정체 가정)
      polyline: DEMO_BASELINE_POLYLINE,
    };
  }
}
