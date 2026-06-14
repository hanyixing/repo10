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
import org.b3log.solo.model.Category;
import org.b3log.solo.model.Tag;
import org.b3log.solo.repository.CategoryRepository;
import org.b3log.solo.repository.CategoryTagRepository;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link CategoryMgmtService} unit test.
 *
 * @author solo-test
 * @version 1.0.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryMgmtService Tests")
public class CategoryMgmtServiceTest {

    @InjectMocks
    private CategoryMgmtService categoryMgmtService;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private CategoryTagRepository categoryTagRepository;

    @Mock
    private Transaction transaction;

    @Test
    @DisplayName("addCategory - should create category with auto-incremented order")
    public void addCategory() throws Exception {
        // Arrange
        final JSONObject category = new JSONObject();
        category.put(Category.CATEGORY_TITLE, "test category");
        category.put(Category.CATEGORY_URI, "test-uri");
        category.put(Category.CATEGORY_DESCRIPTION, "test description");

        when(categoryRepository.getMaxOrder()).thenReturn(5);
        when(categoryRepository.add(any(JSONObject.class))).thenReturn("cat001");

        // Act
        final String categoryId = categoryMgmtService.addCategory(category);

        // Assert
        assertEquals("cat001", categoryId);

        final ArgumentCaptor<JSONObject> recordCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(categoryRepository).add(recordCaptor.capture());
        final JSONObject savedRecord = recordCaptor.getValue();

        assertEquals("test category", savedRecord.getString(Category.CATEGORY_TITLE));
        assertEquals("test-uri", savedRecord.getString(Category.CATEGORY_URI));
        assertEquals("test description", savedRecord.getString(Category.CATEGORY_DESCRIPTION));
        assertEquals(6, savedRecord.getInt(Category.CATEGORY_ORDER));
        assertEquals(0, savedRecord.getInt(Category.CATEGORY_TAG_CNT));
    }

    @Test
    @DisplayName("removeCategory - should remove category and its tag relations")
    public void removeCategory() throws Exception {
        // Arrange
        final String categoryId = "cat001";

        // Act
        categoryMgmtService.removeCategory(categoryId);

        // Assert
        verify(categoryTagRepository).removeByCategoryId(categoryId);
        verify(categoryRepository).remove(categoryId);
    }

    @Test
    @DisplayName("updateCategory - should preserve order and tag count from old category")
    public void updateCategory() throws Exception {
        // Arrange
        final String categoryId = "cat001";
        final JSONObject oldCategory = new JSONObject();
        oldCategory.put(Category.CATEGORY_ORDER, 3);
        oldCategory.put(Category.CATEGORY_TAG_CNT, 5);
        oldCategory.put(Category.CATEGORY_TITLE, "old title");

        final JSONObject newCategory = new JSONObject();
        newCategory.put(Category.CATEGORY_TITLE, "updated title");
        newCategory.put(Category.CATEGORY_URI, "updated-uri");

        when(categoryRepository.get(categoryId)).thenReturn(oldCategory);

        // Act
        categoryMgmtService.updateCategory(categoryId, newCategory);

        // Assert
        final ArgumentCaptor<JSONObject> updateCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(categoryRepository).update(eq(categoryId), updateCaptor.capture());
        final JSONObject updatedCategory = updateCaptor.getValue();

        assertEquals("updated title", updatedCategory.getString(Category.CATEGORY_TITLE));
        assertEquals(3, updatedCategory.getInt(Category.CATEGORY_ORDER));
        assertEquals(5, updatedCategory.getInt(Category.CATEGORY_TAG_CNT));
    }

    @Test
    @DisplayName("changeOrder up - should swap order values with upper category")
    public void changeOrder() throws Exception {
        // Arrange
        final String categoryId = "cat002";

        final JSONObject srcCategory = new JSONObject();
        srcCategory.put(Keys.OBJECT_ID, "cat002");
        srcCategory.put(Category.CATEGORY_ORDER, 5);

        final JSONObject upperCategory = new JSONObject();
        upperCategory.put(Keys.OBJECT_ID, "cat001");
        upperCategory.put(Category.CATEGORY_ORDER, 3);

        when(categoryRepository.beginTransaction()).thenReturn(transaction);
        when(categoryRepository.get(categoryId)).thenReturn(srcCategory);
        when(categoryRepository.getUpper(categoryId)).thenReturn(upperCategory);

        // Act
        categoryMgmtService.changeOrder(categoryId, "up");

        // Assert
        verify(categoryRepository).update(eq("cat002"), argThat(cat -> cat.getInt(Category.CATEGORY_ORDER) == 3));
        verify(categoryRepository).update(eq("cat001"), argThat(cat -> cat.getInt(Category.CATEGORY_ORDER) == 5));
        verify(transaction).commit();
    }

    @Test
    @DisplayName("addCategoryTag - should add relation and update tag count")
    public void addCategoryTag() throws Exception {
        // Arrange
        final String categoryId = "cat001";
        final String tagId = "tag001";

        final JSONObject categoryTag = new JSONObject();
        categoryTag.put(Category.CATEGORY + "_" + Keys.OBJECT_ID, categoryId);
        categoryTag.put(Tag.TAG + "_" + Keys.OBJECT_ID, tagId);

        final JSONObject category = new JSONObject();
        category.put(Category.CATEGORY_TAG_CNT, 0);

        final JSONObject relation1 = new JSONObject();
        final JSONObject relation2 = new JSONObject();
        final List<JSONObject> tagRelations = Arrays.asList(relation1, relation2);

        final JSONObject queryResult = new JSONObject();
        queryResult.put(Keys.RESULTS, (Object) tagRelations);

        when(categoryRepository.get(categoryId)).thenReturn(category);
        when(categoryTagRepository.getByCategoryId(categoryId, 1, Integer.MAX_VALUE)).thenReturn(queryResult);

        // Act
        categoryMgmtService.addCategoryTag(categoryTag);

        // Assert
        verify(categoryTagRepository).add(categoryTag);
        final ArgumentCaptor<JSONObject> updateCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(categoryRepository).update(eq(categoryId), updateCaptor.capture());
        assertEquals(2, updateCaptor.getValue().getInt(Category.CATEGORY_TAG_CNT));
    }

    @Test
    @DisplayName("removeCategoryTag - should remove relation and decrement tag count")
    public void removeCategoryTag() throws Exception {
        // Arrange
        final String categoryId = "cat001";
        final String tagId = "tag001";

        final JSONObject category = new JSONObject();
        category.put(Category.CATEGORY_TAG_CNT, 3);

        when(categoryRepository.get(categoryId)).thenReturn(category);

        // Act
        categoryMgmtService.removeCategoryTag(categoryId, tagId);

        // Assert
        final ArgumentCaptor<JSONObject> updateCaptor = ArgumentCaptor.forClass(JSONObject.class);
        verify(categoryRepository).update(eq(categoryId), updateCaptor.capture());
        assertEquals(2, updateCaptor.getValue().getInt(Category.CATEGORY_TAG_CNT));
        verify(categoryTagRepository).remove(any(org.b3log.latke.repository.Query.class));
    }
}
