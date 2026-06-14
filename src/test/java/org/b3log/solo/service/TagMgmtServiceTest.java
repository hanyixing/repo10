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
import org.b3log.solo.model.Tag;
import org.b3log.solo.repository.CategoryTagRepository;
import org.b3log.solo.repository.TagArticleRepository;
import org.b3log.solo.repository.TagRepository;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link TagMgmtService} unit test.
 *
 * @author solo-test
 * @version 1.0.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TagMgmtService Tests")
public class TagMgmtServiceTest {

    @InjectMocks
    private TagMgmtService tagMgmtService;

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

    @Test
    @DisplayName("removeUnusedTags - should remove tags with zero article references")
    public void removeUnusedTags_removesTagsWithZeroArticles() throws Exception {
        // Arrange
        final JSONObject unusedTag = new JSONObject();
        unusedTag.put(Keys.OBJECT_ID, "unusedTagId");
        unusedTag.put(Tag.TAG_TITLE, "unusedTag");

        final JSONObject usedTag = new JSONObject();
        usedTag.put(Keys.OBJECT_ID, "usedTagId");
        usedTag.put(Tag.TAG_TITLE, "usedTag");

        final List<JSONObject> tags = Arrays.asList(unusedTag, usedTag);

        when(tagRepository.beginTransaction()).thenReturn(transaction);
        when(tagQueryService.getTags()).thenReturn(tags);
        when(tagArticleRepository.getArticleCount("unusedTagId")).thenReturn(0);
        when(tagArticleRepository.getArticleCount("usedTagId")).thenReturn(3);

        // Act
        tagMgmtService.removeUnusedTags();

        // Assert
        verify(categoryTagRepository).removeByTagId("unusedTagId");
        verify(tagRepository).remove("unusedTagId");
        verify(categoryTagRepository, never()).removeByTagId("usedTagId");
        verify(tagRepository, never()).remove("usedTagId");
        verify(transaction).commit();
    }

    @Test
    @DisplayName("removeUnusedTags - should preserve all tags that have article references")
    public void removeUnusedTags_preservesUsedTags() throws Exception {
        // Arrange
        final JSONObject tag1 = new JSONObject();
        tag1.put(Keys.OBJECT_ID, "tag1Id");
        tag1.put(Tag.TAG_TITLE, "keepTag1");

        final JSONObject tag2 = new JSONObject();
        tag2.put(Keys.OBJECT_ID, "tag2Id");
        tag2.put(Tag.TAG_TITLE, "keepTag2");

        final List<JSONObject> tags = Arrays.asList(tag1, tag2);

        when(tagRepository.beginTransaction()).thenReturn(transaction);
        when(tagQueryService.getTags()).thenReturn(tags);
        when(tagArticleRepository.getArticleCount("tag1Id")).thenReturn(2);
        when(tagArticleRepository.getArticleCount("tag2Id")).thenReturn(5);

        // Act
        tagMgmtService.removeUnusedTags();

        // Assert
        verify(tagRepository, never()).remove(anyString());
        verify(categoryTagRepository, never()).removeByTagId(anyString());
        verify(transaction).commit();
    }

    @Test
    @DisplayName("removeUnusedTags - should clean category-tag relations when removing unused tags")
    public void removeUnusedTags_cleansCategoryTagRelations() throws Exception {
        // Arrange
        final JSONObject unusedTag = new JSONObject();
        unusedTag.put(Keys.OBJECT_ID, "catCleanTagId");
        unusedTag.put(Tag.TAG_TITLE, "catCleanTag");

        final List<JSONObject> tags = Collections.singletonList(unusedTag);

        when(tagRepository.beginTransaction()).thenReturn(transaction);
        when(tagQueryService.getTags()).thenReturn(tags);
        when(tagArticleRepository.getArticleCount("catCleanTagId")).thenReturn(0);

        // Act
        tagMgmtService.removeUnusedTags();

        // Assert - category-tag relation is cleaned before tag removal
        final var inOrder = inOrder(categoryTagRepository, tagRepository);
        inOrder.verify(categoryTagRepository).removeByTagId("catCleanTagId");
        inOrder.verify(tagRepository).remove("catCleanTagId");
        verify(transaction).commit();
    }
}
