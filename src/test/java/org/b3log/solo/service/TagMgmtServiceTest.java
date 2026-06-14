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
import org.b3log.latke.repository.Transaction;
import org.b3log.latke.service.ServiceException;
import org.b3log.solo.repository.CategoryTagRepository;
import org.b3log.solo.repository.TagArticleRepository;
import org.b3log.solo.repository.TagRepository;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TagMgmtService} JUnit 5 + Mockito unit test.
 *
 * <p>Unlike {@link TagMgmtServiceTestCase} (TestNG, real H2 database), this test isolates the service
 * by mocking its repositories, verifying the unused-tag removal logic and transaction handling.</p>
 *
 * @version 1.0.0.0, Jun 14, 2026
 */
@ExtendWith(MockitoExtension.class)
public class TagMgmtServiceTest {

    @Mock
    private TagQueryService tagQueryService;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private CategoryTagRepository categoryTagRepository;

    @Mock
    private TagArticleRepository tagArticleRepository;

    @Mock
    private Transaction transaction;

    @InjectMocks
    private TagMgmtService tagMgmtService;

    /**
     * A tag with no articles must be removed; a tag still referenced by articles must be kept.
     */
    @Test
    public void removeUnusedTagsRemovesOrphans() throws Exception {
        final JSONObject orphan = new JSONObject().put(Keys.OBJECT_ID, "orphan");
        final JSONObject used = new JSONObject().put(Keys.OBJECT_ID, "used");
        final List<JSONObject> tags = Arrays.asList(orphan, used);

        when(tagRepository.beginTransaction()).thenReturn(transaction);
        when(tagQueryService.getTags()).thenReturn(tags);
        when(tagArticleRepository.getArticleCount("orphan")).thenReturn(0);
        when(tagArticleRepository.getArticleCount("used")).thenReturn(3);

        tagMgmtService.removeUnusedTags();

        verify(categoryTagRepository).removeByTagId("orphan");
        verify(tagRepository).remove("orphan");
        verify(categoryTagRepository, never()).removeByTagId("used");
        verify(tagRepository, never()).remove("used");
        verify(transaction).commit();
    }

    /**
     * A failure while removing tags must roll the transaction back and surface a {@link ServiceException}.
     */
    @Test
    public void removeUnusedTagsRollsBackOnError() throws Exception {
        when(tagRepository.beginTransaction()).thenReturn(transaction);
        when(tagQueryService.getTags()).thenThrow(new RuntimeException("boom"));
        when(transaction.isActive()).thenReturn(true);

        assertThrows(ServiceException.class, () -> tagMgmtService.removeUnusedTags());

        verify(transaction).rollback();
        verify(transaction, never()).commit();
    }
}
