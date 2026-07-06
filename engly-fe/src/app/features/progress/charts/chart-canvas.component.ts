import {
  AfterViewInit,
  Component,
  ElementRef,
  Input,
  OnChanges,
  OnDestroy,
  ViewChild,
} from '@angular/core';
import { Chart, ChartConfiguration, registerables } from 'chart.js';

// Register controllers/elements/scales once at import time (chart.js v4 is tree-shaken by default,
// so without this the dashboard charts throw "… is not a registered controller").
Chart.register(...registerables);

/**
 * Thin reusable wrapper around a chart.js Chart on a <canvas>. Replaces the ng2-charts
 * directive (which would pull in @angular/cdk). Pass a full ChartConfiguration via [config];
 * the chart is (re)created whenever the input reference changes and destroyed on teardown.
 */
@Component({
  selector: 'app-chart-canvas',
  standalone: true,
  template: `<canvas #canvas role="img" [attr.aria-label]="ariaLabel"></canvas>`,
  styles: [':host { display: block; position: relative; width: 100%; height: 100%; }'],
})
export class ChartCanvasComponent implements AfterViewInit, OnChanges, OnDestroy {
  /** Full chart.js configuration (type, data, options). Change the reference to redraw. */
  @Input({ required: true }) config!: ChartConfiguration<any>;
  /** Accessible description of what the chart shows. */
  @Input() ariaLabel = '';

  @ViewChild('canvas', { static: true }) private canvasRef!: ElementRef<HTMLCanvasElement>;
  private chart?: Chart;

  ngAfterViewInit(): void {
    this.render();
  }

  ngOnChanges(): void {
    // Redraw on input changes after the view exists (initial render handled by ngAfterViewInit).
    if (this.chart) {
      this.render();
    }
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }

  private render(): void {
    this.chart?.destroy();
    if (this.config) {
      this.chart = new Chart(this.canvasRef.nativeElement, this.config);
    }
  }
}
