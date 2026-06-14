/*
 * Solo - A small and beautiful blogging system written in Java.
 * Copyright (c) 2010-present, b3log.org
 *
 * Solo is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *         http://license.coscl.org.cn/MulanPSL2
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */
package org.b3log.solo.service;

import org.b3log.latke.Keys;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Option;
import org.b3log.solo.repository.ArticleRepository;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleQueryService} JUnit 5 + Mockito unit test.
 *
 * <p>Complements {@link ArticleQueryServiceTestCase} (TestNG, real H2 database) by isolating read paths
 * with a mocked {@link ArticleRepository}, covering error fallbacks, access control and sign resolution.</p>
 *
 * @version 1.0.0.0, Jun 14, 2026
 */
@ExtendWith(MockitoExtension.class)
public class ArticleQueryServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @InjectMocks
    private ArticleQueryService articleQueryService;

    @Test
    public void getRecentArticlesReturnsRepositoryResult() throws Exception {
        final List<JSONObject> list = Arrays.asList(new JSONObject(), new JSONObject());
        when(articleRepository.getRecentArticles(5)).thenReturn(list);

        assertEquals(2, articleQueryService.getRecentArticles(5).size());
    }

    @Test
    public void getRecentArticlesReturnsEmptyOnError() throws Exception {
        when(articleRepository.getRecentArticles(anyInt())).thenThrow(new RepositoryException(new RuntimeException("x")));

        assertTrue(articleQueryService.getRecentArticles(5).isEmpty());
    }

    @Test
    public void getArticleByIdReturnsArticle() throws Exception {
        final JSONObject article = new JSONObject().put(Keys.OBJECT_ID, "a1");
        when(articleRepository.get("a1")).thenReturn(article);

        assertSame(article, articleQueryService.getArticleById("a1"));
    }

    @Test
    public void getArticleByIdReturnsNullOnError() throws Exception {
        when(articleRepository.get("a1")).thenThrow(new RepositoryException(new RuntimeException("x")));

        assertNull(articleQueryService.getArticleById("a1"));
    }

    @Test
    public void getRecentArticleTimeReturnsUpdatedTime() throws Exception {
        final JSONObject article = new JSONObject().put(Article.ARTICLE_UPDATED, 12345L);
        when(articleRepository.getRecentArticles(1)).thenReturn(Collections.singletonList(article));

        assertEquals(12345L, articleQueryService.getRecentArticleTime());
    }

    @Test
    public void getRecentArticleTimeReturnsZeroWhenEmpty() throws Exception {
        when(articleRepository.getRecentArticles(1)).thenReturn(Collections.emptyList());

        assertEquals(0, articleQueryService.getRecentArticleTime());
    }

    @Test
    public void hasUpdatedReflectsCreatedVsUpdated() {
        final JSONObject updated = new JSONObject().put(Article.ARTICLE_CREATED, 1L).put(Article.ARTICLE_UPDATED, 2L);
        final JSONObject notUpdated = new JSONObject().put(Article.ARTICLE_CREATED, 5L).put(Article.ARTICLE_UPDATED, 5L);

        assertTrue(articleQueryService.hasUpdated(updated));
        assertFalse(articleQueryService.hasUpdated(notUpdated));
    }

    @Test
    public void getSignReturnsMatchOrDefault() {
        final JSONObject preference = new JSONObject();
        preference.put(Option.ID_C_SIGNS, "[{\"oId\":\"1\",\"signHTML\":\"d\"},{\"oId\":\"2\",\"signHTML\":\"s2\"}]");

        assertEquals("2", articleQueryService.getSign("2", preference).optString(Keys.OBJECT_ID));
        // Unknown sign id falls back to the default sign (oId = "1").
        assertEquals("1", articleQueryService.getSign("999", preference).optString(Keys.OBJECT_ID));
    }

    @Test
    public void canAccessArticleSimpleBranches() throws Exception {
        assertFalse(articleQueryService.canAccessArticle("", new JSONObject()));
        assertFalse(articleQueryService.canAccessArticle("a1", null));

        final JSONObject admin = new JSONObject().put(User.USER_ROLE, Role.ADMIN_ROLE);
        assertTrue(articleQueryService.canAccessArticle("a1", admin));
    }

    @Test
    public void canAccessArticleChecksAuthor() throws Exception {
        final JSONObject article = new JSONObject().put(Article.ARTICLE_AUTHOR_ID, "u1");
        when(articleRepository.get("a1")).thenReturn(article);

        final JSONObject author = new JSONObject().put(Keys.OBJECT_ID, "u1").put(User.USER_ROLE, "defaultRole");
        final JSONObject other = new JSONObject().put(Keys.OBJECT_ID, "u2").put(User.USER_ROLE, "defaultRole");

        assertTrue(articleQueryService.canAccessArticle("a1", author));
        assertFalse(articleQueryService.canAccessArticle("a1", other));
    }
}
