import { HttpParams } from '@angular/common/http';

import { TableQuery } from '../../shared/data-table/data-table.types';

/** A table query as the API's list parameters: page, size, sort, q and filters named after fields. */
export function listParams(query: TableQuery): HttpParams {
  let params = new HttpParams().set('page', query.page).set('size', query.size);
  if (query.sort) {
    params = params.set('sort', query.sort);
  }
  if (query.q) {
    params = params.set('q', query.q);
  }
  for (const [key, value] of Object.entries(query.filters)) {
    params = params.set(key, value);
  }
  return params;
}
