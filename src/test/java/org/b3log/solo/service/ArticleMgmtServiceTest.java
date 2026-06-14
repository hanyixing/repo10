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
import org.b3log.latke.event.EventManager;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.LangPropsService;
import org.b3log.solo.model.Article;
import org.b3log.solo.model.Common;
import org.b3log.solo.repository.*;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link ArticleMgmtService} unit test.
 *
 * @author solo-test
 * @version 1.0.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleMgmtService Tests")
public class ArticleMgmtServiceTest {

    @InjectMocks
    private ArticleMgmtService articleMgmtService;

    @Mock
    private ArticleQueryService articleQueryService;

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private PageRepository pageRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private ArchiveDateRepository archiveDateRepository;

    @Mock
    private ArchiveDateArticleRepository archiveDateArticleRepository;

    @Mock
    private TagArticleRepository tagArticleRepository;

    @Mock
    private CategoryTagRepository categoryTagRepository;

    @Mock
    private PermalinkQueryService permalinkQueryService;

    @Mock
    private EventManager eventManager;

    @Mock
    private LangPropsService langPropsService;

    @Mock
    private StatisticMgmtService statisticMgmtService;

    @Mock
    private StatisticQueryService statisticQueryService;

    @Mock
    private InitService initService;

    @Mock
    private TagMgmtService tagMgmtService;

    @Mock
    private OptionQueryService optionQueryService;

    @Mock
    private OptionMgmtService optionMgmtService;

    @Mock
    private Transaction transaction;

    private JSONObject buildArticleRequest(final String title, final String content, final String tags,
                                           final int status, final String permalink) {
        final JSONObject requestJSONObject = new JSONObject();
        final JSONObject article = new JSONObject();
        requestJSONObject.put(Article.ARTICLE, article);

        article.put(Article.ARTICLE_AUTHOR_ID, "admin001");
        article.put(Article.ARTICLE_TITLE, title);
        article.put(Article.ARTICLE_ABSTRACT, "test abstract");
        article.put(Article.ARTICLE_CONTENT, content);
        article.put(Article.ARTICLE_TAGS_REF, tags);
        article.put(Article.ARTICLE_PERMALINK, permalink);
        article.put(Article.ARTICLE_STATUS, status);
        article.put(Common.POST_TO_COMMUNITY, false);
        article.put(Article.ARTICLE_SIGN_ID, "1");
        article.put(Article.ARTICLE_VIEW_PWD, "");

        return requestJSONObject;
    }

    private void setupAddArticleMocks() throws Exception {
        when(articleRepository.beginTransaction()).thenReturn(transaction);
        when(tagRepository.getByTitle(anyString())).thenReturn(null);
        when(tagRepository.add(any(JSONObject.class))).thenAnswer(invocation -> {
            final JSONObject tag = invocation.getArgument(0);
            final String id = "tag_" + tag.getString("tagTitle");
            tag.put(Keys.OBJECT_ID, id);
            return id;
        });
        when(permalinkQueryService.exist(anyString())).thenReturn(false);
        when(archiveDateRepository.getByArchiveDate(anyString())).thenReturn(null);
        when(archiveDateArticleRepository.getByArticleId(anyString())).thenReturn(null);
    }

    @Test
    @DisplayName("addArticle - should create published article with tags and archive date")
    public void addArticle() throws Exception {
        // Arrange - permalink format must match /articles/yyyy/MM/dd/digits.html
        final JSONObject request = buildArticleRequest(
                "Test Article", "article content", "tag1, tag2",
                Article.ARTICLE_STATUS_C_PUBLISHED, "/articles/2024/01/01/1234567890.html");

        setupAddArticleMocks();

        // Act
        final String articleId = articleMgmtService.addArticle(request);

        // Assert
        assertNotNull(articleId);
        verify(articleRepository).add(any(JSONObject.class));
        verify(tagRepository, times(2)).add(any(JSONObject.class));
        verify(tagArticleRepository, times(2)).add(any(JSONObject.class));
        verify(transaction).commit();
    }

    @Test
    @DisplayName("addArticle - should default to '待分类' when tags are empty")
    public void addArticleWithoutTags() throws Exception {
        // Arrange
        final JSONObject request = buildArticleRequest(
                "No Tags Article", "content", "",
                Article.ARTICLE_STATUS_C_PUBLISHED, "/articles/2024/01/01/1234567891.html");

        setupAddArticleMocks();

        // Act
        final String articleId = articleMgmtService.addArticle(request);

        // Assert
        assertNotNull(articleId);
        final ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        verify(articleRepository).add(captor.capture());
        assertEquals("待分类", captor.getValue().getString(Article.ARTICLE_TAGS_REF));
    }

    @Test
    @DisplayName("addDraftArticle - should create draft without archive date")
    public void addDraftArticle() throws Exception {
        // Arrange
        final JSONObject request = buildArticleRequest(
                "Draft Article", "draft content", "draft",
                Article.ARTICLE_STATUS_C_DRAFT, "/articles/2024/01/01/1234567892.html");

        when(articleRepository.beginTransaction()).thenReturn(transaction);
        when(tagRepository.getByTitle(anyString())).thenReturn(null);
        when(tagRepository.add(any(JSONObject.class))).thenAnswer(invocation -> {
            final JSONObject tag = invocation.getArgument(0);
            tag.put(Keys.OBJECT_ID, "tag_draft");
            return "tag_draft";
        });
        when(permalinkQueryService.exist(anyString())).thenReturn(false);

        // Act
        final String articleId = articleMgmtService.addArticle(request);

        // Assert
        assertNotNull(articleId);
        verify(articleRepository).add(any(JSONObject.class));
        // Draft articles should NOT create archive date entries
        verify(archiveDateRepository, never()).add(any(JSONObject.class));
        verify(archiveDateArticleRepository, never()).add(any(JSONObject.class));
        verify(transaction).commit();
    }

    @Test
    @DisplayName("removeArticle - should remove article and all related data")
    public void removeArticle() throws Exception {
        // Arrange
        final String articleId = "art001";

        // Mock archive date relation (published article has one)
        final JSONObject archiveDateRelation = new JSONObject();
        archiveDateRelation.put(Keys.OBJECT_ID, "archiveRel001");
        archiveDateRelation.put("archiveDate_oId", "archive001");

        // Mock tag relations
        final JSONObject tagRelation1 = new JSONObject();
        tagRelation1.put(Keys.OBJECT_ID, "tagRel001");
        tagRelation1.put("tag_oId", "tag001");
        final JSONObject tagRelation2 = new JSONObject();
        tagRelation2.put(Keys.OBJECT_ID, "tagRel002");
        tagRelation2.put("tag_oId", "tag002");

        when(articleRepository.beginTransaction()).thenReturn(transaction);
        when(archiveDateArticleRepository.getByArticleId(articleId)).thenReturn(archiveDateRelation);
        when(archiveDateArticleRepository.getPublishedArticleCount("archive001")).thenReturn(0);
        when(tagArticleRepository.getByArticleId(articleId))
                .thenReturn(java.util.Arrays.asList(tagRelation1, tagRelation2));
        when(tagArticleRepository.getArticleCount(anyString())).thenReturn(0);

        // Act
        articleMgmtService.removeArticle(articleId);

        // Assert
        verify(archiveDateArticleRepository).remove("archiveRel001");
        verify(archiveDateRepository).remove("archive001");
        verify(tagArticleRepository).remove("tagRel001");
        verify(tagArticleRepository).remove("tagRel002");
        verify(articleRepository).remove(articleId);
        verify(transaction).commit();
    }

    @Test
    @DisplayName("topArticle - should set putTop flag and update repository")
    public void topArticle() throws Exception {
        // Arrange
        final String articleId = "art001";
        final JSONObject article = new JSONObject();
        article.put(Article.ARTICLE_TITLE, "Test Article");
        article.put(Article.ARTICLE_PUT_TOP, false);

        when(articleRepository.beginTransaction()).thenReturn(transaction);
        when(articleRepository.get(articleId)).thenReturn(article);

        // Act
        articleMgmtService.topArticle(articleId, true);

        // Assert
        assertTrue(article.getBoolean(Article.ARTICLE_PUT_TOP));
        verify(articleRepository).update(eq(articleId), any(JSONObject.class), any(String[].class));
        verify(transaction).commit();
    }

    @Test
    @DisplayName("cancelPublishArticle - should change status to draft")
    public void cancelPublishArticle() throws Exception {
        // Arrange
        final String articleId = "art001";
        final JSONObject article = new JSONObject();
        article.put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);

        when(articleRepository.beginTransaction()).thenReturn(transaction);
        when(articleRepository.get(articleId)).thenReturn(article);

        // Act
        articleMgmtService.cancelPublishArticle(articleId);

        // Assert
        assertEquals(Article.ARTICLE_STATUS_C_DRAFT, article.getInt(Article.ARTICLE_STATUS));
        verify(articleRepository).update(eq(articleId), any(JSONObject.class), any(String[].class));
        verify(transaction).commit();
    }

    @Test
    @DisplayName("updateArticlesRandomValue - should refresh random values for articles")
    public void updateArticlesRandomValue() throws Exception {
        // Arrange
        final JSONObject article1 = new JSONObject();
        article1.put(Keys.OBJECT_ID, "art001");
        article1.put(Article.ARTICLE_RANDOM_DOUBLE, 0.1);

        final JSONObject article2 = new JSONObject();
        article2.put(Keys.OBJECT_ID, "art002");
        article2.put(Article.ARTICLE_RANDOM_DOUBLE, 0.2);

        when(articleRepository.beginTransaction()).thenReturn(transaction);
        when(articleRepository.getRandomly(10)).thenReturn(java.util.Arrays.asList(article1, article2));

        // Act
        articleMgmtService.updateArticlesRandomValue(10);

        // Assert
        verify(articleRepository).update(eq("art001"), any(JSONObject.class), any(String[].class));
        verify(articleRepository).update(eq("art002"), any(JSONObject.class), any(String[].class));
        verify(transaction).commit();
        // Random values should have changed
        assertNotEquals(0.1, article1.getDouble(Article.ARTICLE_RANDOM_DOUBLE));
        assertNotEquals(0.2, article2.getDouble(Article.ARTICLE_RANDOM_DOUBLE));
    }
}
