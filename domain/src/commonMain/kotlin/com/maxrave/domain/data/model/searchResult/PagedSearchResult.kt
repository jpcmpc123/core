package com.maxrave.domain.data.model.searchResult

data class PagedSearchResult<T>(
    val items: List<T>,
    val continuation: String? = null,
)
