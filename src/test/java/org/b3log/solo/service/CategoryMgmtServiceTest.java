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
import org.b3log.solo.model.Category;
import org.b3log.solo.repository.CategoryRepository;
import org.b3log.solo.repository.CategoryTagRepository;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CategoryMgmtService} JUnit 5 + Mockito unit test.
 *
 * <p>Complements {@link CategoryMgmtServiceTestCase} (TestNG, real H2 database) by isolating the service
 * with mocked repositories, asserting ordering, tag-count preservation and the order-swap branches.</p>
 *
 * @version 1.0.0.0, Jun 14, 2026
 */
@ExtendWith(MockitoExtension.class)
public class CategoryMgmtServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private CategoryTagRepository categoryTagRepository;

    @Mock
    private Transaction transaction;

    @InjectMocks
    private CategoryMgmtService categoryMgmtService;

    /**
     * Adding a category assigns the next order (max + 1) and returns the new id.
     */
    @Test
    public void addCategorySetsOrderAndReturnsId() throws Exception {
        when(categoryRepository.getMaxOrder()).thenReturn(5);
        when(categoryRepository.add(any(JSONObject.class))).thenReturn("cat123");

        final JSONObject category = new JSONObject();
        category.put(Category.CATEGORY_TITLE, "t");
        category.put(Category.CATEGORY_URI, "u");

        final String id = categoryMgmtService.addCategory(category);

        assertEquals("cat123", id);
        assertEquals(6, category.optInt(Category.CATEGORY_ORDER));
        verify(categoryRepository).add(any(JSONObject.class));
    }

    /**
     * A repository failure during add must be wrapped in a {@link ServiceException}.
     */
    @Test
    public void addCategoryWrapsRepositoryException() throws Exception {
        when(categoryRepository.getMaxOrder()).thenThrow(new RepositoryException(new RuntimeException("x")));

        assertThrows(ServiceException.class, () -> categoryMgmtService.addCategory(new JSONObject()));
    }

    /**
     * Removing a category first removes its category-tag relations, then the category itself.
     */
    @Test
    public void removeCategoryRemovesRelationsThenCategory() throws Exception {
        categoryMgmtService.removeCategory("cat1");

        final InOrder inOrder = inOrder(categoryTagRepository, categoryRepository);
        inOrder.verify(categoryTagRepository).removeByCategoryId("cat1");
        inOrder.verify(categoryRepository).remove("cat1");
    }

    /**
     * Updating a category must keep the existing order and tag count from the stored record.
     */
    @Test
    public void updateCategoryPreservesOrderAndTagCount() throws Exception {
        final JSONObject old = new JSONObject();
        old.put(Category.CATEGORY_ORDER, 3);
        old.put(Category.CATEGORY_TAG_CNT, 7);
        when(categoryRepository.get("cat1")).thenReturn(old);

        final JSONObject update = new JSONObject();
        update.put(Category.CATEGORY_TITLE, "new");

        categoryMgmtService.updateCategory("cat1", update);

        assertEquals(3, update.optInt(Category.CATEGORY_ORDER));
        assertEquals(7, update.optInt(Category.CATEGORY_TAG_CNT));
        verify(categoryRepository).update(eq("cat1"), eq(update));
    }

    /**
     * Moving a category "up" swaps its order with the upper category and commits.
     */
    @Test
    public void changeOrderSwapsWithUpperCategory() throws Exception {
        when(categoryRepository.beginTransaction()).thenReturn(transaction);
        final JSONObject src = new JSONObject().put(Keys.OBJECT_ID, "src").put(Category.CATEGORY_ORDER, 2);
        final JSONObject upper = new JSONObject().put(Keys.OBJECT_ID, "up").put(Category.CATEGORY_ORDER, 1);
        when(categoryRepository.get("src")).thenReturn(src);
        when(categoryRepository.getUpper("src")).thenReturn(upper);

        categoryMgmtService.changeOrder("src", "up");

        assertEquals(1, src.optInt(Category.CATEGORY_ORDER));
        assertEquals(2, upper.optInt(Category.CATEGORY_ORDER));
        verify(categoryRepository).update("src", src);
        verify(categoryRepository).update("up", upper);
        verify(transaction).commit();
    }

    /**
     * When there is no target category to swap with, the transaction rolls back and nothing is updated.
     */
    @Test
    public void changeOrderRollsBackWhenNoTarget() throws Exception {
        when(categoryRepository.beginTransaction()).thenReturn(transaction);
        final JSONObject src = new JSONObject().put(Keys.OBJECT_ID, "src").put(Category.CATEGORY_ORDER, 1);
        when(categoryRepository.get("src")).thenReturn(src);
        when(categoryRepository.getUpper("src")).thenReturn(null);
        when(transaction.isActive()).thenReturn(true);

        categoryMgmtService.changeOrder("src", "up");

        verify(transaction).rollback();
        verify(transaction, never()).commit();
        verify(categoryRepository, never()).update(anyString(), any(JSONObject.class));
    }
}
