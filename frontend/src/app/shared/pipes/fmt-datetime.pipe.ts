import { Pipe, PipeTransform } from '@angular/core';

@Pipe({ name: 'fmtDateTime', standalone: true })
export class FmtDateTimePipe implements PipeTransform {
  transform(value: unknown): string {
    if (!value) return '--';
    const s = String(value);
    const m = s.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/);
    return m ? `${m[3]}/${m[2]}/${m[1]} ${m[4]}:${m[5]}` : s;
  }
}
