import type { GeoPoint } from '@odee/shared-types';

export type SupportedExternalNavi = 'KAKAO_NAVI' | 'TMAP' | 'NAVER_MAP';

export interface NaviTargetPlace extends GeoPoint {
  name: string;
}

export interface NaviLaunchUrls {
  vendor: SupportedExternalNavi;
  labelKo: string;
  appSchemeUrl: string;
  webFallbackUrl: string;
}

/**
 * 합의된 그룹 목적지/경유지를 사용자가 선호하는 스마트폰 내비게이션 앱으로 즉시 넘기는
 * 범용 멀티 내비게이션 딥링크 빌더 (카카오내비 / TMAP / 네이버지도)
 */
export function buildUniversalNaviUrls(
  destination: NaviTargetPlace,
  waypoint?: NaviTargetPlace,
): Record<SupportedExternalNavi, NaviLaunchUrls> {
  const encodedDest = encodeURIComponent(destination.name);
  const wpParamKakao = waypoint
    ? `&via1_name=${encodeURIComponent(waypoint.name)}&via1_x=${waypoint.lng}&via1_y=${waypoint.lat}`
    : '';
  const wpParamTmap = waypoint
    ? `&via1name=${encodeURIComponent(waypoint.name)}&via1x=${waypoint.lng}&via1y=${waypoint.lat}`
    : '';
  const wpParamNaver = waypoint
    ? `&v1lat=${waypoint.lat}&v1lng=${waypoint.lng}&v1name=${encodeURIComponent(waypoint.name)}`
    : '';

  return {
    KAKAO_NAVI: {
      vendor: 'KAKAO_NAVI',
      labelKo: '카카오내비',
      appSchemeUrl: `kakaonavi://navigate?name=${encodedDest}&x=${destination.lng}&y=${destination.lat}&coord_type=wgs84${wpParamKakao}`,
      webFallbackUrl: `https://map.kakao.com/link/to/${encodedDest},${destination.lat},${destination.lng}`,
    },
    TMAP: {
      vendor: 'TMAP',
      labelKo: 'TMAP',
      appSchemeUrl: `tmap://route?goalname=${encodedDest}&goalx=${destination.lng}&goaly=${destination.lat}${wpParamTmap}`,
      webFallbackUrl: `https://apis.openapi.sk.com/tmap/app/routes?goalname=${encodedDest}&goalx=${destination.lng}&goaly=${destination.lat}`,
    },
    NAVER_MAP: {
      vendor: 'NAVER_MAP',
      labelKo: '네이버지도',
      appSchemeUrl: `nmap://navigation?dlat=${destination.lat}&dlng=${destination.lng}&dname=${encodedDest}${wpParamNaver}&appname=com.hyundai.pleos.syncdrive`,
      webFallbackUrl: `https://map.naver.com/p/search/${encodedDest}`,
    },
  };
}

export function launchExternalNavigation(urls: NaviLaunchUrls): void {
  const isMobile = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
  if (!isMobile) {
    window.open(urls.webFallbackUrl, '_blank', 'noopener,noreferrer');
    return;
  }

  const startedAt = Date.now();
  window.location.href = urls.appSchemeUrl;
  setTimeout(() => {
    if (Date.now() - startedAt < 1800 && !document.hidden) {
      window.open(urls.webFallbackUrl, '_blank', 'noopener,noreferrer');
    }
  }, 1400);
}
