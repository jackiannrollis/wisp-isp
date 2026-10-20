import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
} from '@angular/core';
import { BarChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { EChartsCoreOption, EChartsType, init, use } from 'echarts/core';
import { SVGRenderer } from 'echarts/renderers';

import { ThemeModeService } from '../../core/theme/theme-mode.service';

// Tree-shaken (ADR 0002 amendment): only the chart types and parts v1 uses.
use([BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, SVGRenderer]);

/**
 * The one ECharts wrapper. `option` may name colours as CSS variables (`var(--app-success)`); they are
 * resolved against the current theme, and the chart is redrawn when light or dark mode changes. Every
 * chart needs a text equivalent next to it: `label` describes it for screen readers.
 */
@Component({
  selector: 'app-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '',
  host: { role: 'img', '[attr.aria-label]': 'label()' },
  styles: `
    :host {
      display: block;
      width: 100%;
      height: 14rem;
    }
  `,
})
export class ChartComponent {
  readonly option = input.required<EChartsCoreOption>();
  readonly label = input.required<string>();

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly mode = inject(ThemeModeService).mode;
  private readonly destroyRef = inject(DestroyRef);
  private chart: EChartsType | null = null;

  constructor() {
    afterNextRender(() => {
      this.chart = init(this.host.nativeElement, undefined, { renderer: 'svg' });
      this.render();
      const resize = new ResizeObserver(() => this.chart?.resize());
      resize.observe(this.host.nativeElement);
      const media = matchMedia('(prefers-color-scheme: dark)');
      const redraw = () => this.render();
      media.addEventListener('change', redraw);
      this.destroyRef.onDestroy(() => {
        resize.disconnect();
        media.removeEventListener('change', redraw);
        this.chart?.dispose();
      });
    });
    effect(() => {
      this.option();
      this.mode();
      this.render();
    });
  }

  private render(): void {
    if (this.chart) {
      this.chart.setOption(resolveColours(this.option(), this.host.nativeElement), {
        notMerge: true,
      });
    }
  }
}

/** Replaces every `var(--x)` string in the option with the colour it currently resolves to. */
function resolveColours<T>(value: T, element: HTMLElement): T {
  if (typeof value === 'string' && value.startsWith('var(')) {
    const probe = document.createElement('span');
    probe.style.color = value;
    element.appendChild(probe);
    const colour = getComputedStyle(probe).color;
    probe.remove();
    return colour as T;
  }
  if (Array.isArray(value)) {
    return value.map((item) => resolveColours(item, element)) as T;
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([k, v]) => [k, resolveColours(v, element)]),
    ) as T;
  }
  return value;
}
