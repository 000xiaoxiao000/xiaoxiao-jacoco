package peruser;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * 跨进程（MQ）key 透传织入：把「生产者 -&gt; 消息 -&gt; 消费者」的 key 链路打通，
 * 业务代码一行都不用改。
 *
 * <p>此前跨进程只能让业务在消费端手写 {@code CoverageTracer.begin(msg.getProperty(...))}，
 * 那是让【目标系统适应探针】。正确做法是探针自己在客户端层面完成注入（与 OpenTelemetry
 * 把 trace context 写进 carrier 的做法一致）：
 * <pre>
 *   生产端：KafkaProducer.send / DefaultMQProducer.send / ChannelN.basicPublish
 *           -> MqKeyHook.out(record)   把 key 写进 headers / user property（标准扩展区）
 *   消费端：@KafkaListener / @RabbitListener / @RocketMQMessageListener 方法
 *           以及 MessageListener / MessageListenerConcurrently 实现
 *           -> MqKeyHook.in(msg) ... finally { MqKeyHook.outEnd(); }
 * </pre>
 *
 * <p>织入点全部按【类名 / 注解名 / 接口名】匹配，不依赖任何 MQ 的编译期 API，
 * 也不依赖 Spring 内部实现；匹配不上就原样返回（安全降级，绝不破坏目标类）。
 *
 * <p>默认关闭（{@code mqkey=true} 才启用）：给业务消息加 header 属于【改变目标系统的数据】，
 * 必须由使用方显式点头，探针不能自作主张。
 */
public final class MqKeyWeaver {

    private static final String HOOK = "peruser/MqKeyHook";

    // ===================== 生产端：精确类名 + 方法签名（与被插桩范围无关） =====================
    // 这些类是三方客户端，通常不在 includes= 里，因此生产端织入独立于覆盖率插桩单独走一条分支。
    private static final String[][] PRODUCERS = {
            // Kafka：双参 send 是唯一真正落地的重载（单参会转调它）
            {"org/apache/kafka/clients/producer/KafkaProducer", "send",
                    "(Lorg/apache/kafka/clients/producer/ProducerRecord;Lorg/apache/kafka/clients/producer/Callback;)Ljava/util/concurrent/Future;"},
            // RocketMQ
            {"org/apache/rocketmq/client/producer/DefaultMQProducer", "send",
                    "(Lorg/apache/rocketmq/common/message/Message;)Lorg/apache/rocketmq/client/producer/SendResult;"},
            // RabbitMQ：官方 client 的实现类（接口 Channel 无方法体，织不了）
            {"com/rabbitmq/client/impl/ChannelN", "basicPublish",
                    "(Ljava/lang/String;Ljava/lang/String;Lcom/rabbitmq/client/AMQP$BasicProperties;[B)V"},
    };
    /** 各生产端方法里「消息载体」所在的局部变量槽位（与上面顺序一致）。 */
    private static final int[] PRODUCER_SLOT = {1, 1, 3};

    // ===================== 消费端：注解 / 接口 =====================
    /** 类级注解（RocketMQ 的监听器标在类上）。 */
    private static final String[] CLASS_ANNOTATIONS = {
            "Lorg/apache/rocketmq/spring/annotation/RocketMQMessageListener;",
    };
    /** 方法级注解。 */
    private static final String[] METHOD_ANNOTATIONS = {
            "Lorg/springframework/kafka/annotation/KafkaListener;",
            "Lorg/springframework/amqp/rabbit/annotation/RabbitListener;",
    };
    /** 直接实现的监听器接口（原生客户端写法）。 */
    private static final String[] LISTENER_INTERFACES = {
            "org/springframework/kafka/listener/MessageListener",
            "org/springframework/amqp/core/MessageListener",
            "org/apache/rocketmq/client/consumer/listener/MessageListenerConcurrently",
            "org/apache/rocketmq/client/consumer/listener/MessageListenerOrderly",
    };
    /**
     * 消息载体的类型（依次尝试）：命中哪个就把哪个参数当 carrier 传给 MqKeyHook。
     * RocketMQ 批量消费传进来的是 List，hook 内部取第一条。
     */
    private static final String[] CARRIER_TYPES = {
            "Lorg/apache/kafka/clients/consumer/ConsumerRecord;",
            "Lorg/apache/rocketmq/common/message/MessageExt;",
            "Lorg/springframework/amqp/core/Message;",
            "Ljava/util/List;",
    };

    private MqKeyWeaver() {
    }

    /** 生产端快速预判断：O(1) 类名匹配，避免对每个类都做字节码解析。 */
    public static boolean isProducerClass(String className) {
        if (className == null) return false;
        for (String[] p : PRODUCERS) {
            if (p[0].equals(className)) return true;
        }
        return false;
    }

    /** 织入生产端：把方法里的消息载体参数过一遍 MqKeyHook.out（写回原槽位）。 */
    public static byte[] weaveProducer(byte[] buf, String className) {
        int idx = -1;
        for (int i = 0; i < PRODUCERS.length; i++) {
            if (PRODUCERS[i][0].equals(className)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return null;

        final String method = PRODUCERS[idx][1];
        final String desc = PRODUCERS[idx][2];
        final int slot = PRODUCER_SLOT[idx];
        final boolean[] hit = {false};

        ClassReader cr = new ClassReader(buf);
        // 三方客户端类：只重算 maxs，不 COMPUTE_FRAMES（可能引用 agent 看不到的类）
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || !method.equals(name) || !desc.equals(descriptor)) {
                    return mv;
                }
                hit[0] = true;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        mv.visitCode();
                        // 载体 = MqKeyHook.out(载体)   无 key 时 hook 原样返回，消息字节不变
                        mv.visitVarInsn(Opcodes.ALOAD, slot);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "out",
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, carrierInternalOf(descriptor));
                        mv.visitVarInsn(Opcodes.ASTORE, slot);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        return hit[0] ? cw.toByteArray() : null;
    }

    /** 从方法描述符里取出载体参数对应的内部类名，用于 CHECKCAST（槽位固定，类型可推导）。 */
    private static String carrierInternalOf(String descriptor) {
        Type[] args = Type.getArgumentTypes(descriptor);
        for (Type t : CARRIER_TYPES_ASM) {
            for (Type a : args) {
                if (a.equals(t)) return a.getInternalName();
            }
        }
        // 兜底：取第一个引用类型参数（生产端的载体必然是引用类型）
        for (Type a : args) {
            if (a.getSort() == Type.OBJECT) return a.getInternalName();
        }
        return "java/lang/Object";
    }

    private static final Type[] CARRIER_TYPES_ASM = {
            Type.getType("Lorg/apache/kafka/clients/producer/ProducerRecord;"),
            Type.getType("Lorg/apache/rocketmq/common/message/Message;"),
            Type.getType("Lcom/rabbitmq/client/AMQP$BasicProperties;"),
    };

    /**
     * 织入消费端：先判断该类是否是 MQ 监听器（类注解 / 方法注解 / 监听器接口），
     * 是则给「带消息参数的方法」套上 in / outEnd。不匹配返回 null（调用方保持原字节码）。
     */
    public static byte[] weaveConsumer(byte[] buf, ClassLoader loader) {
        final ClassReader cr = new ClassReader(buf);
        final boolean[] isListener = {false};
        final java.util.Set<String> annotated = new java.util.HashSet<>();

        // 第一遍：只读结构（接口 / 注解 / 方法名），判断是否值得织入
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int v, int access, String name, String signature,
                              String superName, String[] interfaces) {
                if (interfaces != null) {
                    for (String i : interfaces) {
                        for (String l : LISTENER_INTERFACES) {
                            if (l.equals(i)) isListener[0] = true;
                        }
                    }
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                for (String a : CLASS_ANNOTATIONS) {
                    if (a.equals(descriptor)) isListener[0] = true;
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                if (name.equals("<init>") || name.equals("<clinit>")) return null;
                final String key = name + descriptor;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                        for (String a : METHOD_ANNOTATIONS) {
                            if (a.equals(d)) {
                                isListener[0] = true;
                                annotated.add(key);
                            }
                        }
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (!isListener[0]) {
            return null;
        }

        // 第二遍：真正织入
        final boolean[] hit = {false};
        ClassWriter cw = new LoaderAwareClassWriter(cr, loader);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || name.equals("<init>") || name.equals("<clinit>")) {
                    return mv;
                }
                int slot = carrierSlot(access, descriptor);
                if (slot < 0) {
                    return mv;
                }
                // 注解方式只对【被标注的方法】织入，避免把同类的其它重载/辅助方法也钩上
                if (!annotated.isEmpty() && !annotated.contains(name + descriptor)) {
                    if (!isRunnableEntry(name, descriptor)) {
                        return mv;
                    }
                }
                hit[0] = true;
                return new ConsumerMethodAdapter(mv, slot);
            }
        }, ClassReader.EXPAND_FRAMES);
        return hit[0] ? cw.toByteArray() : null;
    }

    /** 接口实现类（无注解）时的入口方法名：consumeMessage(RocketMQ) / onMessage(Kafka/AMQP)。 */
    private static boolean isRunnableEntry(String name, String descriptor) {
        return name.equals("onMessage") || name.equals("consumeMessage");
    }

    /**
     * 计算消息载体参数所在的局部变量槽位：按 CARRIER_TYPES 顺序找第一个匹配的参数，
     * 找不到返回 -1（不织入）。long/double 占两个槽位，static 方法从 0 开始。
     */
    private static int carrierSlot(int access, String descriptor) {
        Type[] args = Type.getArgumentTypes(descriptor);
        int slot = ((access & Opcodes.ACC_STATIC) == 0) ? 1 : 0;
        for (Type a : args) {
            for (String c : CARRIER_TYPES) {
                if (a.getDescriptor().equals(c)) {
                    return slot;
                }
            }
            slot += a.getSize();
        }
        return -1;
    }

    /** 消费方法：入口 in(carrier)，所有出口（含抛异常）outEnd()。 */
    private static final class ConsumerMethodAdapter extends MethodVisitor {
        private final int slot;
        private final Label tryStart = new Label();
        private final Label end = new Label();
        private final Label handler = new Label();

        ConsumerMethodAdapter(MethodVisitor mv, int slot) {
            super(Opcodes.ASM9, mv);
            this.slot = slot;
        }

        @Override
        public void visitCode() {
            mv.visitCode();
            mv.visitLabel(tryStart);
            mv.visitVarInsn(Opcodes.ALOAD, slot);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "in", "(Ljava/lang/Object;)V", false);
            mv.visitTryCatchBlock(tryStart, end, handler, null);
        }

        @Override
        public void visitInsn(int opcode) {
            boolean isReturn = (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN)
                    || opcode == Opcodes.RETURN;
            if (isReturn) {
                outEnd();
            }
            mv.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            mv.visitLabel(end);
            mv.visitLabel(handler);
            outEnd();
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitMaxs(maxStack, maxLocals);
        }

        private void outEnd() {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "outEnd", "()V", false);
        }
    }
}
