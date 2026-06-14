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
import org.b3log.latke.model.Pagination;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.service.LangPropsService;
import org.b3log.solo.model.Article;
import org.b3log.solo.repository.*;
import org.b3log.solo.util.Markdowns;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link ArticleQueryService} unit test.
 *
 * @author solo-test
 * @version 1.0.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleQueryService Tests")
public class ArticleQueryServiceTest {

    @InjectMocks
    private ArticleQueryService articleQueryService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private CategoryTagRepository categoryTagRepository;

    @Mock
    private UserQueryService userQueryService;

    @Mock
    private OptionQueryService optionQueryService;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private TagArticleRepository tagArticleRepository;

    @Mock
    private ArchiveDateArticleRepository archiveDateArticleRepository;

    @Mock
    private StatisticQueryService statisticQueryService;

    @Mock
    private LangPropsService langPropsService;

    @Test
    @DisplayName("getArticleById - should return article when found")
    public void getArticleById() throws Exception {
        // Arrange
        final String articleId = "article001";
        final JSONObject article = new JSONObject();
        article.put(Keys.OBJECT_ID, articleId);
        article.put(Article.ARTICLE_TITLE, "Test Article");

        when(articleRepository.get(articleId)).thenReturn(article);

        // Act
        final JSONObject result = articleQueryService.getArticleById(articleId);

        // Assert
        assertNotNull(result);
        assertEquals("Test Article", result.getString(Article.ARTICLE_TITLE));
    }

    @Test
    @DisplayName("getArticleById - should return null when article not found")
    public void getArticleById_notFound() throws Exception {
        // Arrange
        when(articleRepository.get("nonexistent")).thenReturn(null);

        // Act
        final JSONObject result = articleQueryService.getArticleById("nonexistent");

        // Assert
        assertNull(result);
    }

    @Test
    @DisplayName("getRecentArticles - should return recent articles list")
    public void getRecentArticles() throws Exception {
        // Arrange
        final JSONObject article = new JSONObject();
        article.put(Article.ARTICLE_TITLE, "Recent Article");

        when(articleRepository.getRecentArticles(10)).thenReturn(Collections.singletonList(article));

        // Act
        final List<JSONObject> result = articleQueryService.getRecentArticles(10);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Recent Article", result.get(0).getString(Article.ARTICLE_TITLE));
    }

    @Test
    @DisplayName("getRecentArticles - should return empty list on repository error")
    public void getRecentArticles_error() throws Exception {
        // Arrange
        when(articleRepository.getRecentArticles(10)).thenThrow(new RepositoryException("DB error"));

        // Act
        final List<JSONObject> result = articleQueryService.getRecentArticles(10);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("hasUpdated - should return true when updated time differs from created time")
    public void hasUpdated() throws Exception {
        // Arrange
        final JSONObject article = new JSONObject();
        article.put(Article.ARTICLE_CREATED, 1000L);
        article.put(Article.ARTICLE_UPDATED, 2000L);

        // Act
        final boolean result = articleQueryService.hasUpdated(article);

        // Assert
        assertTrue(result);
    }

    @Test
    @DisplayName("hasUpdated - should return false when updated time equals created time")
    public void hasUpdated_notUpdated() throws Exception {
        // Arrange
        final JSONObject article = new JSONObject();
        article.put(Article.ARTICLE_CREATED, 1000L);
        article.put(Article.ARTICLE_UPDATED, 1000L);

        // Act
        final boolean result = articleQueryService.hasUpdated(article);

        // Assert
        assertFalse(result);
    }

    @Test
    @DisplayName("getAuthor - should return article author")
    public void getAuthor() throws Exception {
        // Arrange
        final JSONObject article = new JSONObject();
        article.put(Article.ARTICLE_AUTHOR_ID, "user001");
        article.put(Keys.OBJECT_ID, "article001");

        final JSONObject author = new JSONObject();
        author.put(Keys.OBJECT_ID, "user001");
        author.put(User.USER_NAME, "Solo");

        when(userRepository.get("user001")).thenReturn(author);

        // Act
        final JSONObject result = articleQueryService.getAuthor(article);

        // Assert
        assertNotNull(result);
        assertEquals("Solo", result.getString(User.USER_NAME));
    }

    @Test
    @DisplayName("getAuthor - should fallback to admin when author not found")
    public void getAuthor_fallbackToAdmin() throws Exception {
        // Arrange
        final JSONObject article = new JSONObject();
        article.put(Article.ARTICLE_AUTHOR_ID, "deletedUser");
        article.put(Keys.OBJECT_ID, "article001");

        final JSONObject admin = new JSONObject();
        admin.put(Keys.OBJECT_ID, "adminId");
        admin.put(User.USER_NAME, "Admin");

        when(userRepository.get("deletedUser")).thenReturn(null);
        when(userRepository.getAdmin()).thenReturn(admin);

        // Act
        final JSONObject result = articleQueryService.getAuthor(article);

        // Assert
        assertNotNull(result);
        assertEquals("Admin", result.getString(User.USER_NAME));
    }

    @Test
    @DisplayName("canAccessArticle - admin should always have access")
    public void canAccessArticle_admin() throws Exception {
        // Arrange
        final JSONObject admin = new JSONObject();
        admin.put(User.USER_ROLE, Role.ADMIN_ROLE);

        // Act
        final boolean result = articleQueryService.canAccessArticle("article001", admin);

        // Assert
        assertTrue(result);
    }

    @Test
    @DisplayName("canAccessArticle - should return false for null user")
    public void canAccessArticle_nullUser() throws Exception {
        // Act
        final boolean result = articleQueryService.canAccessArticle("article001", null);

        // Assert
        assertFalse(result);
    }

    @Test
    @DisplayName("canAccessArticle - should return false for blank articleId")
    public void canAccessArticle_blankArticleId() throws Exception {
        // Arrange
        final JSONObject user = new JSONObject();
        user.put(User.USER_ROLE, "defaultRole");

        // Act
        final boolean result = articleQueryService.canAccessArticle("", user);

        // Assert
        assertFalse(result);
    }

    @Test
    @DisplayName("canAccessArticle - author should have access to own article")
    public void canAccessArticle_author() throws Exception {
        // Arrange
        final JSONObject user = new JSONObject();
        user.put(Keys.OBJECT_ID, "user001");
        user.put(User.USER_ROLE, "defaultRole");

        final JSONObject article = new JSONObject();
        article.put(Article.ARTICLE_AUTHOR_ID, "user001");

        when(articleRepository.get("article001")).thenReturn(article);

        // Act
        final boolean result = articleQueryService.canAccessArticle("article001", user);

        // Assert
        assertTrue(result);
    }

    @Test
    @DisplayName("getArticlesRandomly - should return articles with only title and permalink")
    public void getArticlesRandomly() throws Exception {
        // Arrange
        final JSONObject article = new JSONObject();
        article.put(Keys.OBJECT_ID, "art001");
        article.put(Article.ARTICLE_TITLE, "Random Article");
        article.put(Article.ARTICLE_PERMALINK, "/articles/2024/01/01/art001.html");
        article.put(Article.ARTICLE_AUTHOR_ID, "user001");
        article.put(Article.ARTICLE_CONTENT, "content");
        article.put(Article.ARTICLE_ABSTRACT, "abstract");
        article.put(Article.ARTICLE_CREATED, 1000L);
        article.put(Article.ARTICLE_UPDATED, 1000L);
        article.put(Article.ARTICLE_TAGS_REF, "tag1");
        article.put(Article.ARTICLE_RANDOM_DOUBLE, 0.5);
        article.put(Article.ARTICLE_PUT_TOP, false);
        article.put(Article.ARTICLE_VIEW_PWD, "");
        article.put(Article.ARTICLE_SIGN_ID, "1");

        when(articleRepository.getRandomly(5)).thenReturn(Collections.singletonList(article));

        // Act
        final List<JSONObject> result = articleQueryService.getArticlesRandomly(5);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        final JSONObject returnedArticle = result.get(0);
        assertEquals("Random Article", returnedArticle.getString(Article.ARTICLE_TITLE));
        assertEquals("/articles/2024/01/01/art001.html", returnedArticle.getString(Article.ARTICLE_PERMALINK));
        // Internal properties should be removed
        assertFalse(returnedArticle.has(Keys.OBJECT_ID));
        assertFalse(returnedArticle.has(Article.ARTICLE_AUTHOR_ID));
        assertFalse(returnedArticle.has(Article.ARTICLE_CONTENT));
    }

    @Test
    @DisplayName("getNextArticle - should delegate to repository")
    public void getNextArticle() throws Exception {
        // Arrange
        final String articleId = "art001";
        final JSONObject nextArticle = new JSONObject();
        nextArticle.put(Article.ARTICLE_TITLE, "Next Article");

        when(articleRepository.getNextArticle(articleId)).thenReturn(nextArticle);

        // Act
        final JSONObject result = articleQueryService.getNextArticle(articleId);

        // Assert
        assertNotNull(result);
        assertEquals("Next Article", result.getString(Article.ARTICLE_TITLE));
    }

    @Test
    @DisplayName("getPreviousArticle - should delegate to repository")
    public void getPreviousArticle() throws Exception {
        // Arrange
        final String articleId = "art002";
        final JSONObject prevArticle = new JSONObject();
        prevArticle.put(Article.ARTICLE_TITLE, "Previous Article");

        when(articleRepository.getPreviousArticle(articleId)).thenReturn(prevArticle);

        // Act
        final JSONObject result = articleQueryService.getPreviousArticle(articleId);

        // Assert
        assertNotNull(result);
        assertEquals("Previous Article", result.getString(Article.ARTICLE_TITLE));
    }

    @Test
    @DisplayName("markdown - should convert article content and abstract from Markdown to HTML")
    public void markdown() {
        // Arrange
        final JSONObject article = new JSONObject();
        article.put(Keys.OBJECT_ID, "art001");
        article.put(Article.ARTICLE_CONTENT, "**bold text**");
        article.put(Article.ARTICLE_ABSTRACT, "*italic abstract*");

        try (final MockedStatic<Markdowns> mockedMarkdowns = mockStatic(Markdowns.class)) {
            mockedMarkdowns.when(() -> Markdowns.toHTML("**bold text**")).thenReturn("<p><strong>bold text</strong></p>");
            mockedMarkdowns.when(() -> Markdowns.toHTML("*italic abstract*")).thenReturn("<p><em>italic abstract</em></p>");

            // Act
            articleQueryService.markdown(article);

            // Assert
            assertEquals("<p><strong>bold text</strong></p>", article.getString(Article.ARTICLE_CONTENT));
            assertEquals("<p><em>italic abstract</em></p>", article.getString(Article.ARTICLE_ABSTRACT));
            mockedMarkdowns.verify(() -> Markdowns.toHTML("**bold text**"));
            mockedMarkdowns.verify(() -> Markdowns.toHTML("*italic abstract*"));
        }
    }

    @Test
    @DisplayName("searchKeyword - should return matching articles with pagination")
    public void searchKeyword() throws Exception {
        // Arrange
        final JSONObject matchedArticle = new JSONObject();
        matchedArticle.put(Article.ARTICLE_TITLE, "Solo Blog");

        final JSONObject paginationInfo = new JSONObject();
        paginationInfo.put(Pagination.PAGINATION_PAGE_COUNT, 1);

        final JSONObject queryResult = new JSONObject();
        queryResult.put(Pagination.PAGINATION, paginationInfo);
        final java.util.ArrayList<JSONObject> resultsList = new java.util.ArrayList<>();
        resultsList.add(matchedArticle);
        queryResult.put(Keys.RESULTS, (Object) resultsList);

        final JSONObject preference = new JSONObject();
        preference.put("articleListPaginationWindowSize", 15);

        when(articleRepository.get(any(org.b3log.latke.repository.Query.class))).thenReturn(queryResult);
        when(optionQueryService.getPreference()).thenReturn(preference);

        // Act
        final JSONObject result = articleQueryService.searchKeyword("Solo", 1, 20);

        // Assert
        assertNotNull(result);
        assertNotNull(result.opt(Article.ARTICLES));
        final List<JSONObject> articles = (List<JSONObject>) result.opt(Article.ARTICLES);
        assertEquals(1, articles.size());
        assertEquals("Solo Blog", articles.get(0).getString(Article.ARTICLE_TITLE));
    }
}
