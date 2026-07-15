import {useWindowDimensions} from 'react-native';

const BASE_WIDTH = 412;
const BASE_HEIGHT = 892;

export function useResponsive() {
  const {width, height} = useWindowDimensions();
  const scaleW = width / BASE_WIDTH;
  const scaleH = height / BASE_HEIGHT;
  const scale = Math.min(scaleW, scaleH);

  const s = (base: number) => Math.round(base * scale);
  const sWidth = (base: number) => Math.round(base * scaleW);
  const sHeight = (base: number) => Math.round(base * scaleH);
  const pctW = (pct: number) => `${pct}%` as unknown as number;

  return {width, height, scale, scaleW, scaleH, s, sWidth, sHeight, pctW};
}
