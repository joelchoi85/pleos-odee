import type { GeoPoint } from '@odee/shared-types';

const EARTH_RADIUS_METERS = 6_371_000;

function toRad(deg: number): number {
  return (deg * Math.PI) / 180;
}

/**
 * 두 WGS84 좌표 간 대원 거리(m) 계산
 */
export function haversineDistanceMeters(a: GeoPoint, b: GeoPoint): number {
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);

  const sinLat = Math.sin(dLat / 2);
  const sinLng = Math.sin(dLng / 2);
  const h =
    sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLng * sinLng;
  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(h)));
}

/**
 * 점 P에서 선분 AB까지의 최단 직교 거리(m) 계산 (국소 평면 근사)
 */
export function pointToSegmentDistanceMeters(
  p: GeoPoint,
  a: GeoPoint,
  b: GeoPoint,
): number {
  const meanLatRad = toRad((a.lat + b.lat + p.lat) / 3);
  const metersPerDegLat = 111_320;
  const metersPerDegLng = 111_320 * Math.cos(meanLatRad);

  const px = (p.lng - a.lng) * metersPerDegLng;
  const py = (p.lat - a.lat) * metersPerDegLat;
  const bx = (b.lng - a.lng) * metersPerDegLng;
  const by = (b.lat - a.lat) * metersPerDegLat;

  const segLenSq = bx * bx + by * by;
  if (segLenSq === 0) {
    return Math.hypot(px, py);
  }

  const t = Math.max(0, Math.min(1, (px * bx + py * by) / segLenSq));
  const projX = t * bx;
  const projY = t * by;
  return Math.hypot(px - projX, py - projY);
}

/**
 * 차량 현재 위치에서 기준 경로 폴리라인까지의 최단 이탈 거리(Cross-Track Distance, m)
 */
export function crossTrackDistanceToPolylineMeters(
  point: GeoPoint,
  polyline: GeoPoint[],
): number {
  if (polyline.length === 0) return 0;
  if (polyline.length === 1) return haversineDistanceMeters(point, polyline[0]);

  let minDistance = Number.POSITIVE_INFINITY;
  for (let i = 0; i < polyline.length - 1; i++) {
    const dist = pointToSegmentDistanceMeters(
      point,
      polyline[i],
      polyline[i + 1],
    );
    if (dist < minDistance) {
      minDistance = dist;
    }
  }
  return Math.round(minDistance * 10) / 10;
}

/**
 * 트래픽 최적화 가변 위치 전송 주기 산출 (1s ~ 10s)
 */
export function computeAdaptiveIntervalMs(params: {
  speedKmh: number;
  crossTrackMeters: number;
}): number {
  if (params.crossTrackMeters >= 60) {
    return 1000; // 이탈 의심 구간 초고빈도 관제
  }
  if (params.speedKmh <= 2) {
    return 10000; // 정차 상태 트래픽 절감
  }
  if (params.speedKmh < 50) {
    return 2000; // 도심 분기·저속 주행
  }
  return 5000; // 고속 순항 주행
}
