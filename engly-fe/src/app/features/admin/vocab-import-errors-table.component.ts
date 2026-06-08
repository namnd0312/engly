import { Component, input, output } from '@angular/core';
import { ImportErrorPage } from './data/vocab-import.model';

/**
 * Displays a paginated table of per-row import errors.
 * Extracted from vocab-import-page to keep parent under 200 LOC.
 */
@Component({
  selector: 'app-vocab-import-errors-table',
  standalone: true,
  templateUrl: './vocab-import-errors-table.component.html',
})
export class VocabImportErrorsTableComponent {
  readonly errorsPage = input.required<ImportErrorPage>();
  /** Emits the requested page index (0-based). */
  readonly pageChange = output<number>();

  get pages(): number[] {
    const total = this.errorsPage().totalPages;
    return Array.from({ length: total }, (_, i) => i);
  }

  goPage(p: number): void {
    this.pageChange.emit(p);
  }
}
