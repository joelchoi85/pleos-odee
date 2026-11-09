import type { GeoPoint, PoiCategory } from '@odee/shared-types';

export type MapVendorId = 'KAKAO' | 'TMAP' | 'NAVER' | 'DEMO_CORRIDOR';

export interface StandardRouteResult {
  provider: MapVendorId;
  distanceMeters: number;
  durationSeconds: number;
  polyline: GeoPoint[];
}

export interface NearbyPoiItem {
  id: string;
  name: string;
  category: PoiCategory;
  location: GeoPoint;
  distanceMeters: number;
}

/**
 * 특정 지도 사업자에 종속되지 않는 개방형 멀티 맵 라우팅 & POI 인터페이스
 */
export interface MapRoutingProvider {
  readonly vendorId: MapVendorId;
  calculateRoute(
    origin: GeoPoint,
    destination: GeoPoint,
    waypoints?: GeoPoint[],
  ): Promise<StandardRouteResult>;
  searchNearbyPois(
    center: GeoPoint,
    radiusMeters: number,
  ): Promise<NearbyPoiItem[]>;
}
