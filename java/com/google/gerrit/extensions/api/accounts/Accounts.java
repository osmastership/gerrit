// Copyright (C) 2014 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.extensions.api.accounts;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.extensions.client.ListAccountsOption;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public interface Accounts {
  /**
   * Look up an account by ID.
   *
   * <p><strong>Note:</strong> This method eagerly reads the account. Methods that mutate the
   * account do not necessarily re-read the account. Therefore, calling a getter method on an
   * instance after calling a mutation method on that same instance is not guaranteed to reflect the
   * mutation. It is not recommended to store references to {@code AccountApi} instances.
   *
   * @param id any identifier supported by the REST API, including numeric ID, email, or username.
   * @return API for accessing the account.
   * @throws RestApiException if an error occurred.
   */
  AccountApi id(String id) throws RestApiException;

  /**
   * Look up an account by ID. #id(String)
   *
   * <p>See #id(String)
   */
  AccountApi id(int id) throws RestApiException;

  /**
   * Look up the account of the current in-scope user.
   *
   * @see #id(String)
   */
  AccountApi self() throws RestApiException;

  /** Create a new account with the given username and default options. */
  @CanIgnoreReturnValue
  AccountApi create(String username) throws RestApiException;

  /** Create a new account. */
  @CanIgnoreReturnValue
  AccountApi create(AccountInput input) throws RestApiException;

  /**
   * Suggest users for a given query.
   *
   * <p>Example code: {@code suggestAccounts().withQuery("Reviewer").withLimit(5).get()}
   *
   * @return API for setting parameters and getting result.
   */
  SuggestAccountsRequest suggestAccounts() throws RestApiException;

  /**
   * Suggest users for a given query.
   *
   * <p>Shortcut API for {@code suggestAccounts().withQuery(String)}.
   *
   * @see #suggestAccounts()
   */
  SuggestAccountsRequest suggestAccounts(String query) throws RestApiException;

  /**
   * Query users.
   *
   * <p>Example code: {@code query().withQuery("name:John email:example.com").withLimit(5).get()}
   *
   * @return API for setting parameters and getting result.
   */
  QueryRequest query() throws RestApiException;

  /**
   * Query users.
   *
   * <p>Shortcut API for {@code query().withQuery(String)}.
   *
   * @see #query()
   */
  QueryRequest query(String query) throws RestApiException;

  /**
   * API for setting parameters and getting result. Used for {@code suggestAccounts()}.
   *
   * @see #suggestAccounts()
   */
  abstract class SuggestAccountsRequest {
    private String query;
    private int limit;

    /** Execute query and return a list of accounts. */
    public abstract List<AccountInfo> get() throws RestApiException;

    /**
     * Set query.
     *
     * @param query needs to be in human-readable form.
     */
    public SuggestAccountsRequest withQuery(String query) {
      this.query = query;
      return this;
    }

    /**
     * Set limit for returned list of accounts. Optional; server-default is used when not provided.
     */
    public SuggestAccountsRequest withLimit(int limit) {
      this.limit = limit;
      return this;
    }

    public String getQuery() {
      return query;
    }

    public int getLimit() {
      return limit;
    }
  }

  /**
   * API for setting parameters and getting result. Used for {@code query()}.
   *
   * @see #query()
   */
  abstract class QueryRequest {
    private String query;
    private int limit;
    private int start;
    private boolean suggest;
    private Set<ListAccountsOption> options = EnumSet.noneOf(ListAccountsOption.class);

    /** Execute query and return a list of accounts. */
    public abstract List<AccountInfo> get() throws RestApiException;

    /**
     * Set query.
     *
     * @param query needs to be in human-readable form.
     */
    public QueryRequest withQuery(String query) {
      this.query = query;
      return this;
    }

    /**
     * Set limit for returned list of accounts. Optional; server-default is used when not provided.
     */
    public QueryRequest withLimit(int limit) {
      this.limit = limit;
      return this;
    }

    /** Set number of accounts to skip. Optional; no accounts are skipped when not provided. */
    public QueryRequest withStart(int start) {
      this.start = start;
      return this;
    }

    public QueryRequest withSuggest(boolean suggest) {
      this.suggest = suggest;
      return this;
    }

    /** Set an option on the request, appending to existing options. */
    public QueryRequest withOption(ListAccountsOption options) {
      this.options.add(options);
      return this;
    }

    /** Set options on the request, appending to existing options. */
    public QueryRequest withOptions(ListAccountsOption... options) {
      this.options.addAll(Arrays.asList(options));
      return this;
    }

    /** Set options on the request, replacing existing options. */
    public QueryRequest withOptions(Set<ListAccountsOption> options) {
      this.options = options;
      return this;
    }

    public String getQuery() {
      return query;
    }

    public int getLimit() {
      return limit;
    }

    public int getStart() {
      return start;
    }

    public boolean getSuggest() {
      return suggest;
    }

    public Set<ListAccountsOption> getOptions() {
      return options;
    }
  }
}
