import { Pipe, PipeTransform } from '@angular/core';

@Pipe({ name: 'fmtTime', standalone: true })
export class FmtTimePipe implements PipeTransform {
  transform(value: unknown): string {
    if (!value) return '--';
    const s = String(value);
    if (s.includes(':') && s.length >= 5) return s.substring(0, 5);
    return s;
  }
}
