# ADR-0001: ORM 选 MyBatis-Plus

- 状态:已接受(2026-02,盘问 R2-Q9)
- 关联决策:D-9(需求文档 §6.1);关联目标:D-1(学习优先)

## 背景

本项目的第一目标是**学习优先**:把工作中常用的 Java 技术栈完整走一遍(D-1)。ORM 是 Spring 技术栈里分叉最大的选型之一——Spring Data JPA(Hibernate)是英文社区主流,而国内招聘与业务代码中 MyBatis 系占绝对多数;个人读书工具的量级(几百本书、几万 chunk)对两者都毫无压力,纯技术维度无法分出胜负。

## 决策

后端 ORM 采用 **MyBatis-Plus**(随 Spring Boot 3 + JDK 21 + Maven,`mybatis-plus-spring-boot3-starter`)。

## 理由

- **工作面对齐**:学习目标是在国内工作场景"能读业务代码、能裸搭服务",MyBatis-Plus 是国内事实标准,练习它直接服务毕业标准(D-16)。
- **SQL 可见性**:MyBatis 系保留手写 SQL 的口子,复杂查询(如 M4 的 pgvector 向量检索)不必绕 ORM 抽象,`vector` 列与 cosine 距离查询直接写 SQL 即可——这一点与 D-10(pgvector)天然互补。
- 简单 CRUD 由 BaseMapper + LambdaQueryWrapper 免写,不牺牲日常效率。

## 后果

- 不引入 Spring Data JPA;后续如遇强对象图加载需求,不回头,按 MyBatis 路子解决。
- 实体注解(`@TableName` 等)与 JPA 注解不通用,新人若惯用 JPA 需注意。
