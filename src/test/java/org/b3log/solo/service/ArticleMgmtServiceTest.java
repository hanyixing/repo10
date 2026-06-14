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
import org.b3log.latke.repository.RepositoryException;
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.ServiceException;
import org.b3log.solo.model.Article;
import org.b3log.solo.repository.ArticleRepository;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleMgmtService} JUnit 5 + Mockito unit test.
 *
 * <p>Complements {@link ArticleMgmtServiceTestCase} (TestNG, real H2 database) by isolating the simpler
 * transactional state-changes (cancel publish / top) with a mocked {@link ArticleRepository}, verifying
 * commit on success and rollback + {@link ServiceException} on failure. The heavier {@code addArticle}/
 * {@code removeArticle} paths (which touch static caches and many collaborators) stay covered by the
 * TestNG integration case.</p>
 *
 * @version 1.0.0.0, Jun 14, 2026
 */
@ExtendWith(MockitoExtension.class)
public class ArticleMgmtServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private Transaction transaction;

    @InjectMocks
    private ArticleMgmtService articleMgmtService;

    /**
     * Cancelling publication flips the article status to draft and commits.
     */
    @Test
    public void cancelPublishArticleSetsDraftAndCommits() throws Exception {
        final JSONObject article = new JSONObject()
                .put(Keys.OBJECT_ID, "a1")
                .put(Article.ARTICLE_STATUS, Article.ARTICLE_STATUS_C_PUBLISHED);
        when(articleRepository.beginTransaction()).thenReturn(transaction);
        when(articleRepository.get("a1")).thenReturn(article);

        articleMgmtService.cancelPublishArticle("a1");

        assertEquals(Article.ARTICLE_STATUS_C_DRAFT, article.optInt(Article.ARTICLE_STATUS));
        verify(transaction).commit();
    }

    /**
     * A failure while cancelling publication rolls back and surfaces a {@link ServiceException}.
     */
    @Test
    public void cancelPublishArticleRollsBackOnError() throws Exception {
        when(articleRepository.beginTransaction()).thenReturn(transaction);
        when(articleRepository.get("a1")).thenThrow(new RepositoryException(new RuntimeException("x")));
        when(transaction.isActive()).thenReturn(true);

        assertThrows(ServiceException.class, () -> articleMgmtService.cancelPublishArticle("a1"));

        verify(transaction).rollback();
        verify(transaction, never()).commit();
    }

    /**
     * Topping an article sets the put-top flag and commits.
     */
    @Test
    public void topArticleSetsFlagAndCommits() throws Exception {
        final JSONObject article = new JSONObject().put(Keys.OBJECT_ID, "a1");
        when(articleRepository.beginTransaction()).thenReturn(transaction);
        when(articleRepository.get("a1")).thenReturn(article);

        articleMgmtService.topArticle("a1", true);

        assertTrue(article.optBoolean(Article.ARTICLE_PUT_TOP));
        verify(transaction).commit();
    }
}
