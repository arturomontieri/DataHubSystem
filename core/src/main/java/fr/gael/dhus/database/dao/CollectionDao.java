/*
 * Data Hub Service (DHuS) - For Space data distribution.
 * Copyright (C) 2013,2014,2015 GAEL Systems
 *
 * This file is part of DHuS software sources.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package fr.gael.dhus.database.dao;


import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;
import org.hibernate.Hibernate;
import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.orm.hibernate3.HibernateCallback;
import org.springframework.stereotype.Repository;

import com.google.common.collect.ImmutableList;

import fr.gael.dhus.database.dao.interfaces.CollectionProductListener;
import fr.gael.dhus.database.dao.interfaces.DaoEvent;
import fr.gael.dhus.database.dao.interfaces.DaoListener;
import fr.gael.dhus.database.dao.interfaces.DaoUtils;
import fr.gael.dhus.database.dao.interfaces.HibernateDao;
import fr.gael.dhus.database.object.Collection;
import fr.gael.dhus.database.object.Product;
import fr.gael.dhus.database.object.Role;
import fr.gael.dhus.database.object.User;
import fr.gael.dhus.system.config.ConfigurationManager;

/**
 * @author pidancier
 */
@Repository
public class CollectionDao extends HibernateDao<Collection, Long>
{
   final public static String HIDDEN_PREFIX = "#.";
   final public static String COLLECTION_ROOT_NAME = HIDDEN_PREFIX + "root";
   private static Logger logger = Logger.getLogger (CollectionDao.class);

   @Autowired
   private ProductDao productDao;

   @Autowired
   private UserDao userDao;

   @Autowired
   private FileScannerDao fileScannerDao;

   @Autowired
   private ConfigurationManager cfgManager;

   public int count (User user)
   {
      String userString = "";
      // Bypass for Data Right Managers. They can see all products and
      // collections.
      if ( !cfgManager.isDataPublic () && (user != null) &&
         !user.getRoles ().contains (Role.DATA_MANAGER))
      {
         userString =
            "WHERE (" + user.getId () + " in elements(authorizedUsers) OR " +
                     userDao.getPublicData ().getId () +
                     " in elements(authorizedUsers))";
      }
      return DataAccessUtils
         .intResult (find ("select count(*) FROM Collection " + userString));
   }

   /**
    * Retrieves the root collection of all the collections.
    *
    * @return the root collection or new instance of the root collection if not
    *         already existing.
    */
   public Collection getRootCollection ()
   {
      @SuppressWarnings ("unchecked")
      List<Collection> roots =
         (List<Collection>) find ("FROM Collection where name ='" +
            COLLECTION_ROOT_NAME + "'");

      // shall be created on first start of the DHuS by ConfigurationManager
      if ((roots == null) || (roots.size () == 0))
      {
         return null;
      }

      if (roots.size () > 1)
         logger.warn ("Multiple Collection root detected, using first.");

      return (roots.get (0));
   }

   /**
    * retrieve the parent of the passed collection.
    *
    * @param c the collection to retrieve the parent.
    * @return the parent collection, or null if passed collection is the root
    *         one.
    */
   public Collection getParent (final Collection collection)
   {
      // Case of parent collection already initialized by caller.
      if (collection.getParent () != null) return collection.getParent ();

      StringBuilder hql = new StringBuilder ();
      hql.append ("SELECT parent ");
      hql.append ("FROM ").append (Collection.class.getName ()).append (" ");
      hql.append ("WHERE id = ?");
      List<?> result =
            getHibernateTemplate ().find (hql.toString (), collection.getId ());

      if (result.isEmpty ()) return null;

      Object object = result.get (0);
      if (object instanceof Collection)
         return (Collection) object;

      return null;
   }

   public boolean isRoot (Collection collection)
   {
      return (getParent (collection) == null) &&
         collection.getName ().equals (COLLECTION_ROOT_NAME);
   }

   @SuppressWarnings ("unchecked")
   public List<Collection> getSubCollections (final Long id, User user)
   {
      String userString = "";
      // Bypass for Data Right Managers. They can see all products and
      // collections.
      if ( !cfgManager.isDataPublic () && user != null &&
         !user.getRoles ().contains (Role.DATA_MANAGER))
      {
         userString =
            "(" + user.getId () + " in elements(sub.authorizedUsers) or " +
                     userDao.getPublicData ().getId () +
                     " in elements(sub.authorizedUsers)) and ";
      }
      final String userRestriction = userString;

      StringBuilder hql = new StringBuilder ();
      hql.append ("SELECT sub ");
      hql.append ("FROM ").append (entityClass.getName ()).append (" c ");
      hql.append ("LEFT OUTER JOIN ").append ("c.subCollections sub ");
      hql.append ("WHERE ").append (userRestriction);
      hql.append ("c.id = ? ").append ("AND NOT sub.name ");
      hql.append ("LIKE '").append (HIDDEN_PREFIX).append ("%' ");
      hql.append ("ORDER BY sub.name");

      return (List<Collection>) getHibernateTemplate ().find (hql.toString (),
            id);
   }

   /**
    * Checks if the colletion identified by the id has children collections.
    *
    * @param id
    * @return
    */
   public boolean hasChildrenCollection (final Long id, User user)
   {
      String userString = "";
      // Bypass for Data Right Managers. They can see all products and
      // collections.
      if ( !cfgManager.isDataPublic () && (user != null) &&
         !user.getRoles ().contains (Role.DATA_MANAGER))
      {
         userString =
            "(" + user.getId () + " in elements(sub.authorizedUsers) or " +
                     userDao.getPublicData ().getId () +
                     " in elements(sub.authorizedUsers)) and ";
      }
      final String userRestriction = userString;

      StringBuilder hql = new StringBuilder ();
      hql.append ("SELECT count(*) ");
      hql.append ("FROM ").append (entityClass.getName ()).append (" c ");
      hql.append ("LEFT OUTER JOIN ").append ("c.subCollections sub ");
      hql.append ("WHERE ").append (userRestriction);
      hql.append ("c.id = ? ").append ("AND NOT sub.name ");
      hql.append ("LIKE '").append (HIDDEN_PREFIX).append ("%' ");

      List<?> resultList = getHibernateTemplate ().find (hql.toString (), id);
      Long value = (Long) resultList.get(0);
      return value.intValue () > 0;
   }

   @Override
   public void delete (final Collection collection)
   {
      // get children collection
      List<Collection> children = getAllSubCollection (collection);

      // remove references
      Collection parent = getParent (collection);
      if (parent != null)
      {
         parent.getSubCollections ().remove (collection);
         update (parent);
      }
      fileScannerDao.deleteCollectionReferences (collection);
      collection.getSubCollections ().clear ();
      update (collection);

      // delete children collection
      if (!children.isEmpty ())
      {
         for (Collection child : children)
         {
            child.setParent (null);
            delete (child);
         }
      }

      // delete collection
      super.delete (collection);
   }

   @Override
   public void deleteAll ()
   {
      // delete all collection without the root collection
      List<Collection> collections = getAllSubCollection (getRootCollection ());
      for (Collection collection : collections)
         delete (collection);
   }

   public Collection create (Collection collection, User user)
   {
      Collection parent = collection.getParent ();
      HashSet<User> users = new HashSet<User> ();
      if ( (parent == null) && !isRoot (collection))
      {
         collection.setParent (getRootCollection ());
      }

      if (cfgManager.isDataPublic ())
      {
         users.add (userDao.getPublicData ());
      }
      if (user != null)
      {
         users.add (user);
      }
      if (parent != null && !isRoot (collection) && !isRoot (parent))
      {
         for (User u : getAuthorizedUsers (parent))
         {
            if (!u.getId ().equals (user.getId ()))
            {
               users.add (u);
            }
         }
      }
      collection.setAuthorizedUsers (users);
      return super.create (collection);
   }

   /**
    * Checks if the collection contains the passed product.
    *
    * @param collection the collection to check.
    * @param product the product to retrieve in collection.
    * @return true if the product is included in the collection, false
    *         otherwise.
    */
   public boolean contains (final Long cid, final Long pid)
   {
      Collection collection = read(cid);
      Hibernate.initialize (collection.getProducts());
      return collection.getProducts().contains(productDao.read(pid));
   }

   /**
    * Remove a product from a collection. The product should stay in the
    * database.
    *
    * @param cid
    * @param pid
    */
   public void removeProduct (final Long cid, final Long pid, User user)
   {
      Collection collection = read(cid);
      if (collection == null)
      {
         logger.warn ("Unknown collection #" + cid);
         return;
      }
      Product product = productDao.read(pid);
      if (product == null)
      {
         logger.warn ("Unknown product #" + pid);
         return;
      }
      
      Hibernate.initialize (collection.getProducts());
      collection.getProducts().remove(product);
      update(collection);

      fireProductRemoved (new DaoEvent<Collection> (collection), product);
      List<Collection> subCol = getSubCollections (cid, user);
      for (Collection c : subCol)
      {
         removeProduct (c.getId (), pid, user);
      }
   }

   public void removeProducts (final Long cid, final Long[] pids, User user)
   {
      for (Long pid : pids)
         removeProduct (cid, pid, user);
   }

   // Not filtered by user, only called by ProductDao.delete, which must delete
   // all product references
   @SuppressWarnings ("unchecked")
   public List<Collection> getCollectionsOfProduct (final Long product_id)
   {
      return (List<Collection>) getHibernateTemplate ().find (
         "select c " +
         "from Collection c left outer join c.products p " +
         "where p.id = ? ORDER BY c.name", product_id);
   }

   /**
    * THIS METHOD IS NOT SAFE: IT MUST BE REMOVED.
    * TODO: manage access by page.
    * @param collection_id
    * @param user
    * @return
    */
   @SuppressWarnings ("unchecked")
   public List<Long> getProductIds (final Long collection_id, User user)
   {
      // TODO re-check method
      Collection collection = read (collection_id);
      if (collection != null && isRoot (collection))
      {
         return productDao.getAuthorizedProducts (user.getId ());
      }

      StringBuilder hql = new StringBuilder ();
      hql.append ("SELECT p.id ");
      hql.append ("FROM ").append (entityClass.getName ()).append (" c ");
      hql.append ("LEFT OUTER JOIN c.products p ");
      hql.append ("WHERE ").append ("c.id = ").append (collection_id);

      return (List<Long>) getHibernateTemplate ().find (hql.toString ());
   }

   void fireProductAdded (DaoEvent<Collection> e, Product p)
   {
      e.addParameter ("product", p);

      for (DaoListener<?> listener : getListeners ())
      {
         if (listener instanceof CollectionProductListener)
            ((CollectionProductListener) listener).productAdded (e);
      }
   }

   void fireProductRemoved (DaoEvent<Collection> e, Product p)
   {
      e.addParameter ("product", p);

      for (DaoListener<?> listener : getListeners ())
      {
         if (listener instanceof CollectionProductListener)
            ((CollectionProductListener) listener).productRemoved (e);
      }
   }

   @SuppressWarnings ("unchecked")
   public List<Long> getAuthorizedCollections (Long user_id)
   {
      String restiction_query =
            " c WHERE (" + user_id + " in elements(c.authorizedUsers) OR " +
                  userDao.getPublicData ().getId () +
                  " in elements(c.authorizedUsers))";

      if (cfgManager.isDataPublic ()) restiction_query = "";

      return (List<Long>) find ("select id FROM " + entityClass.getName () +
         restiction_query);
   }

   public boolean hasViewableCollection (final Collection c, final User user)
   {
      ArrayList<Collection> list = new ArrayList<Collection> ();
      return checkAuthorizedSubCollection (c, user, list);
   }

   private boolean checkAuthorizedSubCollection (final Collection c,
      final User user, List<Collection> list)
   {
      if (cfgManager.isDataPublic ()) return true;

      List<Collection> subCollection = getAllSubCollection (c);
      for (Collection sub : subCollection)
      {
         List<User> users = getAuthorizedUsers (sub);
         if (users.contains (user) || users.contains (userDao.getPublicData ()))
         {
            return true;
         }
         else
         {
            if (checkAuthorizedSubCollection (sub, user, list))
            {
               return true;
            }
         }
      }
      return false;
   }

   public List<Collection> getAllSubCollection (final Collection c)
   {
      return ImmutableList.copyOf(c.getSubCollections());
   }

   public int countAuthorizedSubCollections (User user, Collection collection)
   {
      return countAuthorizedSubCollections (user, collection, null);
   }

   public int countAuthorizedSubCollections (User user, Collection collection,
      String filter)
   {
      if (collection == null)
         collection = getRootCollection ();

      // TODO Security on filter string
      String collectionPrefix = "c";
      String subCollectionPrefix = "sub";
      StringBuilder qBuilder = new StringBuilder ();

      qBuilder.append ("SELECT count (" + subCollectionPrefix +
         ") FROM Collection " + collectionPrefix + " " + "LEFT OUTER JOIN " +
         collectionPrefix + ".subCollections " + subCollectionPrefix + " ");

      // Builds the WHERE clause.
      qBuilder.append ("WHERE ");
      qBuilder.append (collectionPrefix + ".id = " + collection.getId () +
         " AND NOT " + subCollectionPrefix + ".name like '" + HIDDEN_PREFIX +
         "%' AND ");
      // Bypass for Data Right Managers. They can see all products and
      // collections.
      if ( !cfgManager.isDataPublic () && user != null &&
         !user.getRoles ().contains (Role.DATA_MANAGER))
      {
         qBuilder.append (DaoUtils.userRestriction (user, subCollectionPrefix +
               "."));
         qBuilder.append (" AND ");
      }

      if (filter != null && !filter.isEmpty ())
      {
         qBuilder.append (filter);
         qBuilder.append (" AND ");
      }

      // Cleanup.
      int remIndex = qBuilder.lastIndexOf (" AND ");
      if (remIndex == -1) remIndex = qBuilder.lastIndexOf ("WHERE ");
      qBuilder.delete (remIndex, remIndex + 5);

      return ((Long) getHibernateTemplate ().find (qBuilder.toString ())
         .get (0)).intValue ();
   }

   @SuppressWarnings ("unchecked")
   public List<Collection> getAuthorizedSubCollections (User user,
      Collection collection, String filter, String order, final int skip,
      final int top)
   {
      // TODO Security on filter & orderBy string
      String collectionPrefix = "c";
      String subCollectionPrefix = "sub";
      StringBuilder qBuilder = new StringBuilder ();

      qBuilder.append ("SELECT " + subCollectionPrefix + " FROM Collection " +
         collectionPrefix + " " + "LEFT OUTER JOIN " + collectionPrefix +
         ".subCollections " + subCollectionPrefix + " ");

      // Builds the WHERE clause.
      qBuilder.append ("WHERE ");
      qBuilder.append (collectionPrefix + ".id = " + collection.getId () +
         " AND NOT " + subCollectionPrefix + ".name like '" + HIDDEN_PREFIX +
         "%' AND ");
      // Bypass for Data Right Managers. They can see all products and
      // collections.
      if (user != null && !user.getRoles ().contains (Role.DATA_MANAGER) &&
         !cfgManager.isDataPublic ())
      {
         qBuilder.append ("(");
         qBuilder.append (user.getId ());
         qBuilder.append (" in elements(" + collectionPrefix +
                  ".authorizedUsers)  OR ");
               qBuilder.append (userDao.getPublicData ().getId ());
               qBuilder.append (" in elements(").append (collectionPrefix)
                  .append (".authorizedUsers))");
         qBuilder.append (" AND ");
      }

      if (filter != null && !filter.isEmpty ())
      {
         qBuilder.append (filter);
         qBuilder.append (" AND ");
      }

      // Cleanup.
      int remIndex = qBuilder.lastIndexOf (" AND ");
      if (remIndex == -1) remIndex = qBuilder.lastIndexOf ("WHERE ");
      qBuilder.delete (remIndex, remIndex + 5);

      // Builds the ORDER BY clause.
      if (order != null && !order.isEmpty ())
      {
         qBuilder.append (" ORDER BY ");
         qBuilder.append (order);
      }
      
      final String hql = qBuilder.toString ();

      return getHibernateTemplate().execute(
         new HibernateCallback<List<Collection>> ()
         {
            @Override
            public List<Collection> doInHibernate (Session session)
               throws HibernateException, SQLException
            {
               Query query = session.createQuery (hql);
               if (top >= 0) query.setMaxResults (top);
               if (skip > 0) query.setFirstResult (skip);

               return query.list ();
            }
         });
   }

   public List<Product> getAuthorizedProducts (User user, Collection collection)
   {
      return getAuthorizedProducts (user, collection, null, null, -1, -1);
   }

   @SuppressWarnings ("unchecked")
   public List<Product> getAuthorizedProducts (User user,
      Collection collection, String filter, String order_by, final int skip,
      int top)
   {
      ProductDao.checkProductNumber(top);
      if (top<0) top=ProductDao.getMaxPageSize();
      final int final_top = top;
      
      // TODO Security on filter & orderBy string
      StringBuilder qBuilder = new StringBuilder ();
      String productPrefix = "p";
      String collectionPrefix = "c";

      if (collection != null)
         qBuilder.append ("SELECT " + productPrefix + " FROM Collection " +
            collectionPrefix + " LEFT OUTER JOIN " + collectionPrefix +
            ".products " + productPrefix + " ");

      else
         qBuilder.append ("FROM Product " + productPrefix + " ");

      // Builds the WHERE clause.
      qBuilder.append ("WHERE ");

      // not retrieve unprocessed products
      qBuilder.append ("p.processed = true");

      if (collection != null)
      {
         qBuilder.append (" AND ");
         qBuilder.append (collectionPrefix + ".id = ");
         qBuilder.append (collection.getId ());
      }

      if (filter != null && !filter.isEmpty ())
      {
         qBuilder.append (" AND ");
         qBuilder.append (filter);
      }

      // Builds the ORDER BY clause.
      if (order_by != null && !order_by.isEmpty ())
      {
         qBuilder.append (" ORDER BY ");
         qBuilder.append (order_by);
      }

      final String hql = qBuilder.toString ();

      return getHibernateTemplate().execute(
         new HibernateCallback<List<Product>> ()
         {
            @Override
            public List<Product> doInHibernate (Session session)
               throws HibernateException, SQLException
            {
               Query query = session.createQuery (hql);
               if (final_top >= 0) query.setMaxResults (final_top);
               if (skip > 0) query.setFirstResult (skip);
               query.setReadOnly (true);
               return query.list();
            }
         });
   }

   public int countAuthorizedProducts (User user, Collection collection)
   {
      return countAuthorizedProducts (user, collection, null);
   }

   public int countAuthorizedProducts (User user, Collection collection,
      String filter)
   {
      // TODO Security on filter string
      StringBuilder qBuilder = new StringBuilder ();
      String productPrefix = "p";
      String collectionPrefix = "c";

      if (collection != null)
         qBuilder.append ("SELECT count(" + productPrefix +
            ") FROM Collection " + collectionPrefix + " LEFT OUTER JOIN " +
            collectionPrefix + ".products " + productPrefix + " ");

      else
         qBuilder.append ("SELECT count (*) FROM Product " + productPrefix +
            " ");

      // Builds the WHERE clause.
      qBuilder.append ("WHERE ");

      // not retrieve unprocessed products
      qBuilder.append ("p.processed = true");

      if (collection != null)
      {
         qBuilder.append (" AND ");
         qBuilder.append (collectionPrefix + ".id = ");
         qBuilder.append (collection.getId ());
      }

      if (filter != null && !filter.isEmpty ())
      {
         qBuilder.append (" AND ");
         qBuilder.append (filter);
      }
      
      return ((Long) getHibernateTemplate ().find (qBuilder.toString ())
         .get (0)).intValue ();
   }

   @SuppressWarnings ("unchecked")
   public List<Collection> getHigherCollections (final User user,
         final String filter, final String order, final int skip, final int top)
   {
      if (user.getRoles ().contains (Role.DATA_MANAGER))
      {
         // Collection.subCollection EAGER loading
         ArrayList<Collection> result =
               new ArrayList<> (getRootCollection ().getSubCollections ());

         // skip collection
         for (int i = 0; i < skip; i++)
            result.remove (0);

         // keep n first collections
         while (result.size () > top)
            result.remove (result.size () - 1);

         return result;
      }
      StringBuilder hql = new StringBuilder ("FROM Collection sub WHERE "
                  + "(? in elements(sub.authorizedUsers) AND "
                  + "? not in elements(sub.parent.authorizedUsers))"
                  + "OR (? in elements(sub.authorizedUsers) AND "
                  + "? not in elements(sub.parent.authorizedUsers))");

      if (filter != null && !(filter.isEmpty ()))
      {
         hql.append (" AND ").append (filter);
      }
      if (order != null && !(order.isEmpty ()))
      {
         hql.append (" ORDER BY ").append (order);
      }

      final String script = hql.toString ();
      
      return getHibernateTemplate().execute(
         new HibernateCallback<List<Collection>> ()
         {
            @Override
            public List<Collection> doInHibernate (Session session)
               throws HibernateException, SQLException
            {
               Query query = session.createQuery (script);
               query.setEntity (0, user);
               query.setEntity (1, user);
               query.setEntity (2, userDao.getPublicData ());
               query.setEntity (3, userDao.getPublicData ());

               if (top >= 0) query.setMaxResults (top);
               if (skip > 0) query.setFirstResult (skip);

               return query.list ();
            }
         });
   }

   @SuppressWarnings ("unchecked")
   public List<User> getAuthorizedUsers (final Collection collection)
   {
      String hql =
            "SELECT users FROM fr.gael.dhus.database.object.Collection c " +
                  "LEFT OUTER JOIN c.authorizedUsers users WHERE c.id = ?";
      return (List<User>) getHibernateTemplate ().find (hql,
            collection.getId ());
   }

   public Long getCollectionByName (final String collection_name)
   {
      String hql =
            "SELECT id FROM " + entityClass.getName () + " WHERE name = ?";
      List<?> result = getHibernateTemplate ().find (hql, collection_name);

      if (result.isEmpty ())
         return null;
      return (Long) result.get (0);
   }


   public boolean hasAccessToCollection (final Long cid, final Long uid)
   {
      if (cid == null || uid == null)
         return false;

      Collection collection = read (cid);
      User user = userDao.read (uid);
      
      if (collection == null || user == null)
         return false;

      if (user.getRoles ().contains (Role.DATA_MANAGER))
         return true;
      
      return collection.getAuthorizedUsers().contains(user);
   }

   public Iterator<Collection> getAllCollectons ()
   {
      String query = "FROM " + entityClass.getName ();
      return new PagedIterator<> (this, query);
   }
}
