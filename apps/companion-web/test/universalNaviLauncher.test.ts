import { describe, expect, it } from 'vitest';
import { buildUniversalNaviUrls } from '../src/utils/universalNaviLauncher.js';

describe('universalNaviLauncher multi-map deep links', () => {
  it('generates valid URI schemes for KakaoNavi, TMAP, and Naver Map with waypoints', () => {
    const urls = buildUniversalNaviUrls(
      { name: '현대모터스튜디오 서울', lat: 37.5214, lng: 127.0342 },
      { name: '판교 E-pit 초급속충전소', lat: 37.4038, lng: 127.0985 },
    );

    expect(urls.KAKAO_NAVI.appSchemeUrl).toContain('kakaonavi://navigate?');
    expect(urls.KAKAO_NAVI.appSchemeUrl).toContain('via1_x=127.0985');

    expect(urls.TMAP.appSchemeUrl).toContain('tmap://route?');
    expect(urls.TMAP.appSchemeUrl).toContain('goalx=127.0342');
    expect(urls.TMAP.appSchemeUrl).toContain('via1y=37.4038');

    expect(urls.NAVER_MAP.appSchemeUrl).toContain('nmap://navigation?');
    expect(urls.NAVER_MAP.appSchemeUrl).toContain('dlat=37.5214');
    expect(urls.NAVER_MAP.appSchemeUrl).toContain('v1lat=37.4038');
  });
});
